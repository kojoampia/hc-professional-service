package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * That a failed write says nothing about where the sibling lives.
 *
 * <h3>Why this exists</h3>
 * Backlog item 24 gave the collection reads an exception that deliberately carries <b>no cause</b>,
 * because {@code ExceptionTranslator} builds the problem {@code detail} from the cause's message in
 * preference to the exception's own, and a {@code RestClient} message quotes the URL it called. It
 * did not give the writes the same treatment, and the review of that item measured what was left
 * behind, on the {@code prod} profile:
 *
 * <pre>
 * PROBE write status = 500
 * PROBE write detail = hc-patient-service: Name or service not known
 * </pre>
 *
 * <p>The {@code prod} scrub does not catch it: {@code containsPackageName} looks for {@code org.},
 * {@code java.}, {@code net.}, {@code com.}, {@code io.}, {@code de.} and a hostname contains none of
 * them. So the sibling's internal name reached a public error body — reachable, despite item 24's own
 * table claiming both writes answered 503, whenever the reads succeed and the write does not: a
 * partial outage, a POST timing out under load, DNS flapping between the six reads and the write.
 *
 * <p><b>The counterfactual is the point of the assertions below.</b> Delete the {@code catch} in
 * {@code post} and the cause is
 * {@code I/O error on POST request for "http://hc-patient-service:8081/api/activity-logs"}, so a test
 * that only asserted the status would stay green through the whole defect. These read the message.
 *
 * <p>A real socket and the client's real request factory, like its two sibling tests: the failure is
 * in what the transport says about itself, which no mock of {@code RestClient} can reproduce.
 */
class PatientServiceClientWriteFailureTest {

    /**
     * A host that cannot resolve, standing in for the deployed {@code hc-patient-service}.
     *
     * <p>{@code .invalid} is reserved by RFC 6761 and guaranteed never to resolve, so this is the
     * DNS failure the probe hit, deterministically and without a network. The name is the sibling's
     * real one because the assertions look for it in the message: a test using {@code example.com}
     * would pass while leaking.
     */
    private static final String UNRESOLVABLE_SIBLING = "http://hc-patient-service.invalid:8081";

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void authenticateACaller() {
        // Both writes refuse without a token to relay, and would never reach the transport.
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("doctor", "a-token-that-is-never-verified", List.of()));
    }

    @AfterEach
    void stopServer() {
        SecurityContextHolder.clearContext();
        if (server != null) {
            server.stop(0);
        }
    }

    /** A sibling that answers whatever {@link #status} says, with a body naming its own host. */
    private PatientServiceClient siblingAnswering(int answerWith) throws IOException {
        status.set(answerWith);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // A problem body of the shape hc-patient really sends, host included — so that a client
            // that forwarded a sibling's body rather than writing its own would fail these tests.
            byte[] body = "{\"detail\":\"http://hc-patient-service:8081 says no\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new PatientServiceClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), true, 5, 30);
    }

    private PatientServiceClient clientForAnUnreachableSibling() {
        return new PatientServiceClient(RestClient.builder(), UNRESOLVABLE_SIBLING, true, 2, 5);
    }

    /** Everything a caller could read off this throwable, message and cause chain together. */
    private static String everythingSaidBy(Throwable thrown) {
        StringBuilder said = new StringBuilder();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            said.append(current.getMessage()).append('\n');
        }
        return said.toString();
    }

    /**
     * The leak itself: a POST that cannot reach the sibling names neither the host nor the port.
     *
     * <p>The cause is asserted absent as well as the message being clean, because the two are the
     * same rule — {@code ExceptionTranslator} prefers the cause's message, so a cause is a leak
     * whatever this exception's own message says.
     */
    @Test
    void aPostThatCannotReachTheSiblingLeaksNeitherHostnameNorPort() {
        Throwable thrown = catchThrowable(() -> clientForAnUnreachableSibling().createActivityLog(Map.of("summary", "Wound dressed")));

        assertThat(thrown).isInstanceOf(PatientServiceUnavailableException.class);
        assertThat(thrown.getCause()).as("a cause is a leak: the translator prefers its message").isNull();
        assertThat(everythingSaidBy(thrown)).doesNotContain("hc-patient-service").doesNotContain("8081").contains("/api/activity-logs");
        assertThat(((PatientServiceUnavailableException) thrown).fault()).isEqualTo(PatientServiceUnavailableException.Fault.TRANSPORT);
    }

    /** The same for the PATCH, which had the same raw propagation. */
    @Test
    void aPatchThatCannotReachTheSiblingLeaksNothingEither() {
        Throwable thrown = catchThrowable(
            () -> clientForAnUnreachableSibling().patchClinicalCase("case-1", Map.of("id", "case-1", "diagnosis", "Something"))
        );

        assertThat(thrown).isInstanceOf(PatientServiceUnavailableException.class);
        assertThat(thrown.getCause()).isNull();
        assertThat(everythingSaidBy(thrown)).doesNotContain("hc-patient-service").doesNotContain("8081").contains("/api/clinical-cases");
    }

    /**
     * A refusal keeps the sibling's status, and that asymmetry with the reads is deliberate.
     *
     * <p>A 4xx means the sibling <em>answered</em>: it read the request and would not have it.
     * Reporting that as 503 would be item 24's conflation one layer out — {@code mobile/}'s offline
     * queue classifies 4xx as {@code rejected} and 5xx as {@code retry}, so a permanently malformed
     * entry reported as unavailability would be retried for ever.
     *
     * <p>The status travels and the body does not: the sibling's problem detail here quotes its own
     * host, and none of it reaches the caller.
     */
    @Test
    void aRefusedWriteKeepsTheSiblingsStatusAndDiscardsItsBody() throws IOException {
        PatientServiceClient client = siblingAnswering(400);

        Throwable thrown = catchThrowable(() -> client.createReport(Map.of("name", "assessment.pdf")));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(everythingSaidBy(thrown)).doesNotContain("hc-patient-service").contains("/api/reports");
    }

    /**
     * 401 is the one 4xx that is not passed through.
     *
     * <p>It does not mean the caller is unauthenticated here — this service authenticated them before
     * the write began — it means the token relayed was not accepted over there, which in practice is
     * the shared signing key having drifted between the stacks. Passing it through would sign a
     * clinician out of a portal that is working.
     */
    @Test
    void a401FromTheSiblingIsUnavailabilityRatherThanTheCallersProblem() throws IOException {
        PatientServiceClient client = siblingAnswering(401);

        assertThatThrownBy(() -> client.createActivityLog(Map.of("summary", "x")))
            .isInstanceOf(PatientServiceUnavailableException.class)
            .hasMessageContaining("UPSTREAM_STATUS");
    }

    /** A 5xx is the sibling failing rather than refusing, so it is unavailability like any other. */
    @Test
    void aSiblingErrorIsUnavailability() throws IOException {
        PatientServiceClient client = siblingAnswering(500);

        Throwable thrown = catchThrowable(() -> client.createActivityLog(Map.of("summary", "x")));

        assertThat(thrown).isInstanceOf(PatientServiceUnavailableException.class);
        assertThat(everythingSaidBy(thrown)).doesNotContain("hc-patient-service");
    }
}
