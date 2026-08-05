package net.jojoaddison.web.rest;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.core.io.ByteArrayResource;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Proves that the servlet container accepts document uploads above 1 MB (MOB4).
 *
 * <h3>Why this cannot be a MockMvc test</h3>
 * {@code OnboardingFlowIT} already asserts that a 5,000,001-byte upload is rejected, and it passes —
 * but it builds {@link org.springframework.mock.web.MockMultipartFile} objects directly and hands
 * them to a mock request. <strong>The servlet container's multipart parser never runs</strong>, so
 * {@code spring.servlet.multipart.max-file-size} is not applied and the test would pass no matter
 * what that property said. That blind spot is exactly why a 1 MB ceiling sat in production
 * unnoticed while the code and its tests both claimed 5 MB.
 *
 * <p>So this test starts a real server on a real port and posts a real multipart body. It is the
 * only test in the repository that exercises container-level request parsing, and it fails against
 * the previous configuration.
 *
 * <p>Authentication has to be a genuine JWT for the same reason — {@code @WithMockUser} populates a
 * thread-local {@code SecurityContext} that a request arriving over a socket on a different thread
 * never sees.
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
        profileRepository.save(new Profile().accountId(APPLICANT).firstName("Upload").lastName("Limit"));
    }

    @AfterEach
    void cleanUp() {
        personalDocumentRepository.deleteAll();
        profileRepository.deleteAll();
    }

    @Test
    void containerLimitsSitAboveTheApplicationLimitSoTheAppsOwnErrorWins() {
        // 6 MB > the resource's 5,000,000-byte check, so an oversize upload is answered by the
        // application's clear message rather than an opaque container failure.
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

    @Test
    void aTwoMegabyteUploadIsAcceptedByTheContainer() {
        ResponseEntity<String> response = upload(jpegOf(TWO_MEGABYTES), "scan.jpg", MediaType.IMAGE_JPEG_VALUE);

        // Before the fix this was a 500 from MaxUploadSizeExceededException raised by the container
        // before any controller code ran.
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
        // Asserted against the translator rather than over the wire. Past the container ceiling
        // Tomcat aborts the connection mid-upload instead of completing a response, so the client
        // sees an I/O error and there is no status code to assert — the over-the-wire behaviour is
        // real but untestable deterministically. In production nginx caps the body at 8m first and
        // answers 413 itself.
        //
        // What IS worth pinning is the mapping: MaxUploadSizeExceededException carries no
        // @ResponseStatus and is not an ErrorResponse, so without the entry added to
        // getMappedStatus() it resolves to 500 and tells the user the server broke.
        var response = exceptionTranslator.handleAnyException(
            new MaxUploadSizeExceededException(multipartProperties.getMaxFileSize().toBytes()),
            new ServletWebRequest(new MockHttpServletRequest("POST", "/api/onboarding/documents"))
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

    private ResponseEntity<String> upload(byte[] content, String filename, String contentType) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", new HttpEntity<>(resource, partHeaders));
        body.add("type", DocumentType.CERTIFICATE.name());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(tokenFor(APPLICANT));

        return restTemplate.exchange(
            "http://localhost:" + port + "/api/onboarding/documents",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );
    }

    /** Mints a token with the same claim set the gateway issues: sub + space-delimited auth. */
    private String tokenFor(String login) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(now.plus(5, ChronoUnit.MINUTES))
            .subject(login)
            .claim(AUTHORITIES_KEY, "ROLE_USER")
            .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(JWT_ALGORITHM).build(), claims)).getTokenValue();
    }
}
