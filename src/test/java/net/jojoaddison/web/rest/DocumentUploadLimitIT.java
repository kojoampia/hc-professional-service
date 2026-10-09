package net.jojoaddison.web.rest;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import net.jojoaddison.config.AsyncSyncConfiguration;
import net.jojoaddison.config.EmbeddedMongo;
import net.jojoaddison.config.JacksonConfiguration;
import net.jojoaddison.config.TestJacksonConfiguration;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Proves that a real socket carries a multi-megabyte document all the way to the application, and
 * that the application's own ceiling is the rung that answers (MOB4, and T2's transport change).
 *
 * <h3>⚠ WHAT THIS CLASS IS ABOUT CHANGED IN T2, AND THE OLD HEADING WOULD NOW BE FALSE</h3>
 *
 * <p>It read <i>"proves that the servlet container accepts document uploads above 1 MB"</i>, and its
 * whole argument was about {@code spring.servlet.multipart.max-file-size}: Spring's 1 MB default
 * fired at the container before any controller code ran, so every upload between 1 MB and 5 MB failed
 * with an opaque {@code MaxUploadSizeExceededException} while the code and its tests both claimed
 * 5 MB. <b>That property no longer applies to this endpoint at all.</b> {@code profile.md} step 3
 * specifies the document as a {@code data: byte[]} field on a JSON body, so
 * {@code POST /api/personal-document} is not a multipart request and the container's multipart parser
 * is never entered. The two cases that read those properties are kept below and re-documented rather
 * than deleted — the values remain correct for the shape they describe — but they are now assertions
 * about configuration that nothing in this service reaches.
 *
 * <p><b>What survives, and it is the half that mattered:</b> a real server on a real port, a real
 * request body of several megabytes, and a real JWT. The reason is unchanged and still worth stating.
 * {@code OnboardingFlowIT} asserts the same refusals through {@code MockMvc}, which hands a prepared
 * body to a mock request — <strong>no container parsing happens</strong>, so that class would pass
 * whatever any transport-level limit said. This class is the only one in the repository that would
 * notice.
 *
 * <h3>⛔ The ceiling chain is now two rungs, and base64 moved the second one</h3>
 *
 * <p>{@code OwnPersonalDocumentResource.MAX_BYTES} (5,000,000, a clear 400 naming the limit) and then
 * nginx's {@code client_max_body_size 8m}, which caps the <em>raw</em> body. Base64 is 4/3 of the
 * document, so that cap is reached at roughly <b>6 MB of document</b> rather than 8 — above the
 * application's own check, so the clear message still wins across the band a clinician's camera
 * produces, and past it the answer is a bare 413 with no body. ⚠ Nothing here can assert nginx's
 * value: it lives in {@code deploy/}, which is a different repository.
 *
 * <p>Authentication has to be a genuine JWT — {@code @WithMockUser} populates a thread-local
 * {@code SecurityContext} that a request arriving over a socket on a different thread never sees.
 */
@SpringBootTest(
    classes = {
        net.jojoaddison.ProfessionalServiceApp.class,
        JacksonConfiguration.class,
        TestJacksonConfiguration.class,
        AsyncSyncConfiguration.class,
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // A successful upload publishes entity.created, and the embedded Kafka broker is shared across
    // every test context for speed — so publishing here would land on the same topic
    // DomainEventsKafkaIT asserts against and it would consume this test's event instead of its own.
    // application.kafka.enabled=false is the documented kill switch: DomainEventPublisher returns
    // before streamBridge.send, so no binding is created and nothing retries. An upload-limit test
    // has no business emitting domain events anyway.
    properties = { "application.kafka.enabled=false" }
)
@EmbeddedMongo
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentUploadLimitIT {

    private static final String APPLICANT = "upload-limit-applicant";

    /**
     * The gateway {@code User.id} for that login. {@code Profile.accountId} holds this since
     * backlog.md item 50, and {@link #tokenFor} puts it on the token — the only class here that mints
     * a real one over a real transport, so it is also the only one that would notice if the claim set
     * drifted from what the gateway issues.
     */
    private static final String ACCOUNT_ID = "uid-upload-limit-applicant";

    /** Between Spring's 1 MB default and the application's own 5 MB check — the broken range. */
    private static final int TWO_MEGABYTES = 2 * 1024 * 1024;

    @LocalServerPort
    private int port;

    /**
     * Constructed directly rather than injected: Spring Boot 4 no longer auto-registers a
     * {@code TestRestTemplate} bean, and this test only needs a plain HTTP client pointed at the
     * random port.
     */
    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    private net.jojoaddison.web.rest.errors.ExceptionTranslator exceptionTranslator;

    @BeforeEach
    void seedProfile() {
        profileRepository.save(new Profile().accountId(ACCOUNT_ID).firstName("Upload").lastName("Limit"));
    }

    @AfterEach
    void cleanUp() {
        personalDocumentRepository.deleteAll();
        profileRepository.deleteAll();
    }

    /**
     * The declared multipart limits still sit above the application's own check.
     *
     * <p>⚠ <b>Inert for this endpoint since T2</b> — the upload is a JSON body, so nothing in this
     * service enters the multipart parser and these two properties gate no request that is made
     * today. Kept because the relationship they encode is the one a future multipart endpoint needs
     * (the container's ceiling above the application's, so the clear message wins) and because
     * deleting the assertion is how the 1 MB default came back last time.
     */
    @Test
    void theDeclaredMultipartLimitsStillSitAboveTheApplicationLimit() {
        assertThat(multipartProperties.getMaxFileSize().toBytes()).isGreaterThan(5_000_000L);
        assertThat(multipartProperties.getMaxRequestSize().toBytes()).isGreaterThanOrEqualTo(
            multipartProperties.getMaxFileSize().toBytes()
        );
    }

    @Test
    void theProductionConfigDeclaresTheSameLimitsThisTestRunsUnder() throws Exception {
        // src/test/resources/config/application.yml REPLACES the main config on the test classpath
        // rather than layering onto it, so everything above would still pass if production had been
        // left on Spring's 1 MB default. Read the real file and compare, otherwise this whole class
        // proves only that the test config is correct.
        String production = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/config/application.yml"));

        assertThat(production)
            .as("production multipart limits must match the values this test exercises")
            .contains("max-file-size: " + humanReadable(multipartProperties.getMaxFileSize().toBytes()))
            .contains("max-request-size: " + humanReadable(multipartProperties.getMaxRequestSize().toBytes()));
    }

    private String humanReadable(long bytes) {
        return (bytes / (1024 * 1024)) + "MB";
    }

    /**
     * A 2 MB document reaches the handler and is stored — over a socket, with its size recorded from
     * the decoded bytes rather than from the base64 that carried them.
     *
     * <p>2 MB is where the historical defect lived (between Spring's 1 MB multipart default and the
     * application's 5 MB check) and it is still the useful size: the request body is ~2.7 MB of JSON,
     * which is past every default anyone is tempted to leave alone.
     */
    @Test
    void aTwoMegabyteDocumentIsAcceptedOverARealTransport() {
        ResponseEntity<String> response = upload(jpegOf(TWO_MEGABYTES), "scan.jpg", MediaType.IMAGE_JPEG_VALUE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(personalDocumentRepository.findAll()).hasSize(1);
        assertThat(personalDocumentRepository.findAll().get(0).getSizeBytes()).isEqualTo(TWO_MEGABYTES);
    }

    @Test
    void anOversizeUploadIsRejectedWithTheApplicationsOwnMessage() {
        ResponseEntity<String> response = upload(jpegOf(5_000_001), "huge.jpg", MediaType.IMAGE_JPEG_VALUE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("5 MB");
        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    @Test
    void aBodyBeyondTheContainerCeilingIsTheClientsFaultNotAServerError() {
        // Asserted against the translator rather than over the wire, and ⚠ NOTHING RAISES THIS ON
        // THE UPLOAD PATH ANY MORE — a JSON body never enters the multipart parser (T2). Kept as a
        // mapping assertion: MaxUploadSizeExceededException carries no @ResponseStatus and is not an
        // ErrorResponse, so without the entry in getMappedStatus() any future multipart endpoint
        // would resolve it to 500 and tell the user the server broke. In production nginx caps the
        // body at 8m — reached at ~6 MB of document once base64 is counted — and answers 413 itself,
        // with no body.
        //
        // What IS worth pinning is the mapping: MaxUploadSizeExceededException carries no
        // @ResponseStatus and is not an ErrorResponse, so without the entry added to
        // getMappedStatus() it resolves to 500 and tells the user the server broke.
        var response = exceptionTranslator.handleAnyException(
            new MaxUploadSizeExceededException(multipartProperties.getMaxFileSize().toBytes()),
            new ServletWebRequest(new MockHttpServletRequest("POST", "/api/personal-document"))
        );

        // Compared numerically: Spring renamed 413 from PAYLOAD_TOO_LARGE to CONTENT_TOO_LARGE and
        // the two enum constants are not equal to each other.
        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }

    // ------------------------------------------------------------------ helpers

    /** A byte array with a valid JPEG magic number, which the resource verifies. */
    private byte[] jpegOf(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    /**
     * One {@code POST /api/personal-document} over the real port, carrying profile.md's specified
     * body.
     *
     * <p>The JSON is assembled by hand rather than through an object mapper, deliberately: this class
     * exists to exercise what actually goes down the wire, and a serializer configured differently
     * from the server's would hide exactly the kind of mismatch it is here to catch.
     */
    private ResponseEntity<String> upload(byte[] content, String filename, String contentType) {
        String body =
            "{\"name\":\"" +
            filename +
            "\",\"type\":\"" +
            DocumentType.CERTIFICATE.name() +
            "\",\"dataContentType\":\"" +
            contentType +
            "\",\"data\":\"" +
            Base64.getEncoder().encodeToString(content) +
            "\"}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokenFor(APPLICANT));

        return restTemplate.exchange(
            "http://localhost:" + port + "/api/personal-document",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );
    }

    /**
     * Mints a token with the same claim set the gateway issues: sub, iss, the {@code uid} carrying
     * {@code User.id}, and space-delimited authorities.
     *
     * <p>{@code iss} and {@code uid} are not decoration here. Since backlog.md item 50 the service
     * resolves its caller from {@code uid}, and only from a token this stack's own gateway minted —
     * so a claim set without them authenticates and then resolves to nobody, and every request in
     * this class 401s before reaching the multipart handling it is about.
     */
    private String tokenFor(String login) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(now.plus(5, ChronoUnit.MINUTES))
            .subject(login)
            .issuer(net.jojoaddison.security.SecurityUtils.MINTING_ISSUER)
            .claim(net.jojoaddison.security.SecurityUtils.UID_KEY, ACCOUNT_ID)
            .claim(AUTHORITIES_KEY, "ROLE_USER")
            .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(JWT_ALGORITHM).build(), claims)).getTokenValue();
    }
}
