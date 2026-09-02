package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

/**
 * That {@link PatientServiceClient} reads a whole collection rather than its first page.
 *
 * <h3>Why this exists, and why nothing else caught it</h3>
 * patientservice's collection endpoints are generated JHipster resources: every one of them takes a
 * {@code Pageable} and, given no {@code size}, serves <b>twenty rows</b>. This client asked for none,
 * so it received twenty and treated them as the whole collection — while the javadoc on each method
 * said "every patient profile" and "every clinical case".
 *
 * <p>Nothing saw it for six weeks because <b>volume is what makes it visible</b>. Every unit test
 * stubs the client, {@link PatientServiceClientTransportTest} stubs a single-element collection, and
 * the integration tests have no patientservice to talk to at all. The quality fixture held seven
 * patients until 2026-09-02; when it was reloaded with ~600 patients and ~1260 cases, a doctor with
 * 100 patients saw 19 — nineteen being simply how many of the first twenty {@code /api/profiles} rows
 * happened to be theirs.
 *
 * <p>So a test here must <b>span a page boundary</b>. Stubbing one page proves nothing: it is exactly
 * what the broken client already passed. Each case below serves more rows than one page holds and
 * asserts the client returns all of them.
 *
 * <p>It is a sibling of {@link PatientServiceClientTransportTest} and shares its shape deliberately —
 * a real {@link HttpServer} on a real socket, the client's real request factory, no Spring context and
 * no Docker. The two ask different questions of the same layer: that one, whether a request reaches
 * the wire at all; this one, whether enough of them do.
 */
class PatientServiceClientPagingTest {

    /** Rows the fake sibling holds. Larger than one page, and not a multiple of it. */
    private static final int COLLECTION_SIZE = 2 * PatientServiceClient.PAGE_SIZE + 50;

    private HttpServer server;

    /** Every query string the client sent, in order. The record of how it paged. */
    private final List<String> queriesSeen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void authenticateACaller() {
        // The client relays the caller's own token and skips the read entirely without one.
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

    /**
     * A sibling that pages properly: {@code size} rows from {@code page * size}, and a short or empty
     * page when the collection runs out. This is what every generated resource in hc-patient does.
     */
    private void sibling(int rows) {
        serve(exchange -> {
            Map<String, String> query = queryOf(exchange);
            int size = Integer.parseInt(query.getOrDefault("size", "20"));
            int page = Integer.parseInt(query.getOrDefault("page", "0"));
            int from = Math.min(page * size, rows);
            int to = Math.min(from + size, rows);
            return jsonRows(IntStream.range(from, to));
        });
    }

    private void serve(RowSource source) {
        try {
            // Port 0: the OS picks a free one, so a parallel build cannot collide.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake sibling", e);
        }
        server.createContext("/", exchange -> {
            queriesSeen.add(String.valueOf(exchange.getRequestURI().getQuery()));
            byte[] body = source.rowsFor(exchange).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @FunctionalInterface
    private interface RowSource {
        String rowsFor(HttpExchange exchange);
    }

    /**
     * Rows minimal enough to deserialize into any of the five DTOs, so the reflective sweep below can
     * call every read against one server.
     */
    private static String jsonRows(IntStream indices) {
        return indices
            .mapToObj(i -> "{\"id\":\"row-" + i + "\",\"patientId\":\"patient-" + i + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private static Map<String, String> queryOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                parsed.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return parsed;
    }

    private PatientServiceClient clientForThisServer() {
        return new PatientServiceClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), true, 5);
    }

    /**
     * The defect itself: a collection larger than one page comes back whole.
     *
     * <p>Against the unfixed client this returns twenty rows — the sibling's default page size — and
     * fails on the very first assertion.
     */
    @Test
    void readsEveryPageOfACollectionThatSpansAPageBoundary() {
        sibling(COLLECTION_SIZE);

        List<String> patientIds = clientForThisServer().profiles().stream().map(p -> p.patientId()).toList();

        assertThat(patientIds).hasSize(COLLECTION_SIZE);
        // Not merely the right count: the right rows, once each. A client that asked for page 0 three
        // times would have the count and none of the patients past the first page.
        assertThat(patientIds).doesNotHaveDuplicates().contains("patient-0", "patient-" + (COLLECTION_SIZE - 1));
        assertThat(queriesSeen).hasSize(3);
        assertThat(queriesSeen.get(0)).contains("page=0").contains("size=" + PatientServiceClient.PAGE_SIZE);
    }

    /**
     * The boundary case that off-by-one gets wrong: a collection that is an exact multiple of the page
     * size. The last full page is indistinguishable from a middle one, so the client has to ask once
     * more and be told there is nothing left.
     */
    @Test
    void readsACollectionThatEndsOnAnExactPageBoundary() {
        int exactlyTwoPages = 2 * PatientServiceClient.PAGE_SIZE;
        sibling(exactlyTwoPages);

        assertThat(clientForThisServer().clinicalCases()).hasSize(exactlyTwoPages);
        assertThat(queriesSeen).hasSize(3);
        assertThat(queriesSeen.get(2)).contains("page=2");
    }

    /**
     * A sibling that ignores {@code page} — which several resources in this estate genuinely do — must
     * terminate, not spin.
     *
     * <p>This is the failure the other direction: asking such an endpoint for page 1 hands back the
     * same rows again, so a pager that trusted the parameter would append the collection to itself
     * until the runaway guard tripped. The client stops as soon as a page contributes nothing new, so
     * the whole collection is returned exactly once and it costs one extra request rather than
     * {@value PatientServiceClient#MAX_PAGES}.
     */
    @Test
    void stopsWhenTheSiblingIgnoresPage() {
        serve(exchange -> jsonRows(IntStream.range(0, PatientServiceClient.PAGE_SIZE)));

        List<String> patientIds = clientForThisServer().activityLogs().stream().map(a -> a.patientId()).toList();

        assertThat(patientIds).hasSize(PatientServiceClient.PAGE_SIZE).doesNotHaveDuplicates();
        assertThat(queriesSeen).hasSize(2);
    }

    /**
     * The last line of defence: a sibling that answers every page with rows nobody has seen before
     * cannot hold a request thread for ever.
     *
     * <p>Unlike the case above there is no repetition to detect — every page is new and plausible — so
     * only the page cap can stop it. It is a guard rather than a limit anyone should reach, and it
     * logs at ERROR when it does.
     */
    @Test
    void theRunawayGuardTerminatesAnEndlessSibling() {
        AtomicInteger served = new AtomicInteger();
        serve(exchange -> {
            int offset = served.getAndIncrement() * PatientServiceClient.PAGE_SIZE;
            return jsonRows(IntStream.range(offset, offset + PatientServiceClient.PAGE_SIZE));
        });

        assertThat(clientForThisServer().medications()).hasSize(PatientServiceClient.MAX_PAGES * PatientServiceClient.PAGE_SIZE);
        assertThat(queriesSeen).hasSize(PatientServiceClient.MAX_PAGES);
    }

    /**
     * A page that fails mid-collection answers empty, not truncated.
     *
     * <p>Empty is a signal this codebase already reads: {@code DutyRosterService.refreshSnapshots}
     * treats an empty profile list as an outage and keeps the stored snapshots rather than blanking
     * them. A half-collection carries no such signal — it is the shape of this very defect, and every
     * caller would filter it and render a short list as a quiet week.
     */
    @Test
    void aFailedPageAnswersEmptyRatherThanTruncated() {
        List<Integer> statuses = new ArrayList<>();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake sibling", e);
        }
        server.createContext("/", exchange -> {
            queriesSeen.add(String.valueOf(exchange.getRequestURI().getQuery()));
            boolean firstPage = queriesSeen.size() == 1;
            statuses.add(firstPage ? 200 : 500);
            byte[] body =
                (firstPage ? jsonRows(IntStream.range(0, PatientServiceClient.PAGE_SIZE)) : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(firstPage ? 200 : 500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        assertThat(clientForThisServer().reports()).isEmpty();
        assertThat(statuses).containsExactly(200, 500);
    }

    /**
     * Every collection read pages, including the ones written after this test.
     *
     * <p><b>Derived, not enumerated.</b> The list of reads is taken from the class by reflection —
     * every public no-argument method that answers a {@link List} — so a read added tomorrow is
     * covered tomorrow. A test naming the five that exist today stops covering things the moment a
     * sixth is written, which is exactly how the vocabulary sweeps in this repo have gone wrong before
     * (see backlog item 8).
     */
    @Test
    void everyCollectionReadAsksForAPage() throws Exception {
        sibling(PatientServiceClient.PAGE_SIZE + 5);
        PatientServiceClient client = clientForThisServer();

        List<Method> reads = java.util.Arrays.stream(PatientServiceClient.class.getDeclaredMethods())
            .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
            .filter(m -> m.getParameterCount() == 0)
            .filter(m -> List.class.equals(m.getReturnType()))
            .toList();

        assertThat(reads).as("the reflective sweep must match the collection reads that exist").isNotEmpty();
        for (Method read : reads) {
            queriesSeen.clear();
            assertThat((List<?>) read.invoke(client)).as(read.getName()).hasSize(PatientServiceClient.PAGE_SIZE + 5);
            assertThat(queriesSeen).as(read.getName()).allSatisfy(query -> assertThat(query).contains("page=").contains("size="));
        }
    }
}
