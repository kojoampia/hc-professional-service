package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

/**
 * {@link PatientServiceClient#profileByAccountId(String)} — the account-keyed profile read
 * (backlog.md rows 140 and 221).
 *
 * <h3>Why the path is what this asserts</h3>
 *
 * <p><b>The defect this guards is a plural {@code s}.</b> {@code /api/profiles/{id}} already means
 * <em>the profile's own id</em> in every product of this estate, so {@code /api/profiles/{accountId}}
 * would collide with it on one pattern and resolve to the wrong document — or to none. Row 140 records
 * the singular {@code /api/profile/{accountId}} as how that ambiguity was removed rather than routed
 * around, and a one-character slip here is invisible in review and silent at runtime: the sibling
 * answers 404, this client swallows it by design, and the caller sees an unresolved subject exactly as
 * it would for an account that does not exist.
 *
 * <p>So this test records the <b>request line</b> rather than the method. The sibling's own tests cover
 * what the endpoint does; only the caller can be wrong about which endpoint it is.
 *
 * <p>A real {@link HttpServer} over a real socket, like {@code PatientServiceClientTransportTest} and
 * for a weaker version of the same reason — a mocked {@code RestClient} would assert the URI template
 * this code passes in, which is the thing under test, so it could not fail. Expanding the template is
 * the step that has to be observed.
 */
class PatientServiceClientAccountReadTest {

    private HttpServer server;
    private final List<String> pathsSeen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            pathsSeen.add(exchange.getRequestURI().getPath());
            // A profile document, with the two id spaces row 221 is migrating between deliberately
            // holding different values — a fixture where they match cannot tell them apart.
            String json = "{\"id\":\"profile-7\",\"patientId\":\"patient-7\",\"accountId\":\"acct-7\",\"firstName\":\"Ama\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    /** This client relays the caller's token and refuses without one — see {@code profileByAccountId}. */
    @BeforeEach
    void authenticateACaller() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("admin", "a-token-that-is-never-verified", List.of()));
    }

    @AfterEach
    void stopServer() {
        SecurityContextHolder.clearContext();
        server.stop(0);
    }

    private PatientServiceClient clientForThisServer() {
        return new PatientServiceClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), true, 5, 30);
    }

    @Test
    void readsTheSingularProfilePathAndNotThePluralCollectionOne() {
        clientForThisServer().profileByAccountId("acct-7");

        // Singular. `/api/profiles/acct-7` would be a different endpoint with a different meaning.
        assertThat(pathsSeen).containsExactly("/api/profile/acct-7");
    }

    @Test
    void returnsTheProfileWithBothIdSpacesIntact() {
        var profile = clientForThisServer().profileByAccountId("acct-7").orElseThrow();

        // Both, and distinct: the migration needs the account id without losing the key everything
        // still reads, and a DTO that dropped either would fail here rather than in production.
        assertThat(profile.accountId()).isEqualTo("acct-7");
        assertThat(profile.patientId()).isEqualTo("patient-7");
    }

    @Test
    void aBlankAccountIdMakesNoRequestAtAll() {
        // Not merely "returns empty": a blank subject must not become a request the sibling has to
        // answer, and asserting the empty Optional alone would pass for a client that asked anyway.
        assertThat(clientForThisServer().profileByAccountId("  ")).isEmpty();
        assertThat(clientForThisServer().profileByAccountId(null)).isEmpty();

        assertThat(pathsSeen).isEmpty();
    }

    @Test
    void anUnauthenticatedCallerMakesNoRequestEither() {
        // The client has no service account to fall back on, and inventing one would bypass the
        // authorization patientservice applies to the caller's own token.
        SecurityContextHolder.clearContext();

        assertThat(clientForThisServer().profileByAccountId("acct-7")).isEmpty();
        assertThat(pathsSeen).isEmpty();
    }
}
