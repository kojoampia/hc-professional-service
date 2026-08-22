package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

/**
 * That {@link PatientServiceClient} can actually put its requests on a socket.
 *
 * <h3>Why this exists, and why nothing else caught it</h3>
 * The client was built on {@code SimpleClientHttpRequestFactory}, which wraps
 * {@code java.net.HttpURLConnection} — a class that <b>cannot send PATCH</b>. It throws
 * {@code java.net.ProtocolException: Invalid HTTP method: PATCH} before a byte leaves the process.
 *
 * <p>Every read here is a GET and every other write is a POST, so the limitation was invisible until
 * {@code patchClinicalCase} was added. That shipped with a green build: the unit tests stub the
 * service, and the integration tests have no patientservice to talk to, so
 * {@code PatientServiceClient} fails soft and returns an empty list either way. The first thing that
 * noticed was a clinician editing a case against the deployed stack and getting a 500.
 *
 * <p>So this test deliberately uses <b>the real request factory over a real socket</b>. A test that
 * mocks {@code RestClient} asserts that this code calls the right method with the right body, which
 * was never in doubt — the fault was one layer below, in the JDK's HTTP client, where no amount of
 * mocking reaches. A local {@link HttpServer} costs a few milliseconds and covers the layer that
 * actually broke.
 *
 * <p>It is a plain unit test rather than an {@code *IT}: it needs no Spring context, no Mongo and no
 * Docker, and making it an IT would put it behind a profile that is easy to skip.
 */
class PatientServiceClientTransportTest {

    private HttpServer server;
    private final List<String> methodsSeen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        // Port 0: the OS picks a free one, so a parallel build cannot collide.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            methodsSeen.add(exchange.getRequestMethod());
            // A GET here reads a collection and a write reads one document, so the shape has to
            // follow the method or Jackson fails before the assertion about the method is reached —
            // and the failure would read as a transport problem rather than a fixture one.
            String json = "{\"id\":\"case-1\",\"patientId\":\"p1\",\"status\":\"OPEN\"}";
            byte[] body = ("GET".equals(exchange.getRequestMethod()) ? "[" + json + "]" : json).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    /**
     * A caller token, because both writes refuse without one.
     *
     * <p>That refusal is deliberate — this client relays the <b>caller's</b> token and never a
     * service account, which is what lets patientservice apply its own authorization — so a test of
     * the write path has to supply one. The value is never verified by the local server; it only has
     * to exist.
     */
    @BeforeEach
    void authenticateACaller() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("doctor", "a-token-that-is-never-verified", List.of()));
    }

    @AfterEach
    void stopServer() {
        SecurityContextHolder.clearContext();
        server.stop(0);
    }

    private PatientServiceClient clientForThisServer() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new PatientServiceClient(RestClient.builder(), baseUrl, true, 5);
    }

    /**
     * The one that fails on {@code SimpleClientHttpRequestFactory}.
     *
     * <p>Revert the factory in {@link PatientServiceClient} and this throws
     * {@code ProtocolException: Invalid HTTP method: PATCH} rather than failing an assertion — which
     * is the shape the deployed 500 had.
     */
    @Test
    void patchReachesTheWire() {
        var updated = clientForThisServer().patchClinicalCase("case-1", Map.of("id", "case-1", "diagnosis", "Something"));

        assertThat(methodsSeen).containsExactly("PATCH");
        assertThat(updated).isNotNull();
        assertThat(updated.id()).isEqualTo("case-1");
    }

    /** The methods that always worked, so a factory swap cannot fix PATCH by breaking these. */
    @Test
    void getStillReachesTheWire() {
        clientForThisServer().clinicalCases();

        assertThat(methodsSeen).containsExactly("GET");
    }

    @Test
    void postStillReachesTheWire() {
        clientForThisServer().createActivityLog(Map.of("summary", "x"));

        assertThat(methodsSeen).containsExactly("POST");
    }
}
