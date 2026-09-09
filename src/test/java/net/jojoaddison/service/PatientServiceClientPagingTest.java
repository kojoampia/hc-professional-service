package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * That {@link PatientServiceClient} reads a whole collection rather than its first page, and that
 * reading it stays bounded.
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
 * <p><b>The second half of the subject is the cost of the first.</b> Paging turned one bounded request
 * into many, so the two ways a read can stop without ending — the wall-clock budget and the page guard
 * — are asserted here as well, and both must fail rather than serve what they collected.
 *
 * <p><b>"Fail" means {@link PatientServiceUnavailableException} since backlog item 24</b>, and the
 * distinction these cases now assert is the whole of that item: an unread collection and an empty one
 * were the same value, so the same empty list told {@code DutyRosterService} that a stack was down and
 * {@code PatientDirectoryService} that a patient was not in a clinician's caseload. Each case below
 * therefore has a partner asserting that a genuinely empty collection is still an empty list — a
 * failure signal nobody can tell from a legitimate answer is no better than none.
 *
 * <p>It is a sibling of {@link PatientServiceClientTransportTest} and shares its shape deliberately —
 * a real {@link HttpServer} on a real socket, the client's real request factory, no Spring context and
 * no Docker. The two ask different questions of the same layer: that one, whether a request reaches
 * the wire at all; this one, whether enough of them do.
 */
class PatientServiceClientPagingTest {

    /** Rows the fake sibling holds. Larger than one page, and not a multiple of it. */
    private static final int COLLECTION_SIZE = 2 * PatientServiceClient.PAGE_SIZE + 50;

    /** Per-page timeout, in seconds. Generous: nothing here is meant to reach it. */
    private static final int PAGE_TIMEOUT_SECONDS = 5;

    /** Whole-collection budget, in seconds. Generous for every case but the one that tests it. */
    private static final int READ_BUDGET_SECONDS = 30;

    private HttpServer server;

    /** Every query string the client sent, in order. The record of how it paged. */
    private final List<String> queriesSeen = new CopyOnWriteArrayList<>();

    /**
     * Every status the fake sibling answered with, in order.
     *
     * <p>Concurrent, like {@link #queriesSeen}: both are written from {@link HttpServer}'s handler
     * thread and read from the test thread.
     */
    private final List<Integer> statusesSent = new CopyOnWriteArrayList<>();

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
            return Response.ok(jsonRows(IntStream.range(from, to)));
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
            Response response = source.responseFor(exchange);
            statusesSent.add(response.status());
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    /**
     * One answer from the fake sibling.
     *
     * <p>The status is part of it so that a test needing a non-200 does not have to rebuild
     * {@link #serve}'s scaffolding — which is what the failed-page case used to do, at the cost of a
     * second {@link HttpServer#create} that would have leaked the first one had the two ever run
     * together.
     */
    private record Response(int status, String body) {
        static Response ok(String body) {
            return new Response(200, body);
        }
    }

    @FunctionalInterface
    private interface RowSource {
        Response responseFor(HttpExchange exchange);
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
        return clientForThisServer(READ_BUDGET_SECONDS);
    }

    /**
     * A deployment with no sibling answers empty, and that is not the conflation item 24 removed.
     *
     * <p>The item's method is that every branch of the empty-or-failed question gets a paired
     * assertion, and this was the one branch that had none: every other construction in this class
     * passes {@code true}, so a later simplification that raised unconditionally would break the
     * supported no-sibling configuration with a green build. `enabled=false` is the same statement
     * {@code application.kafka.enabled=false} makes about the broker — the collection is genuinely
     * empty because there is nothing to read, not because a read failed.
     *
     * <p>Asserted with no HTTP server behind it at all: the point is that the client must not call.
     */
    @Test
    void aDeploymentWithNoSiblingAnswersEmptyRatherThanRaising() {
        PatientServiceClient disabled = new PatientServiceClient(
            RestClient.builder(),
            "http://127.0.0.1:1",
            false,
            PAGE_TIMEOUT_SECONDS,
            READ_BUDGET_SECONDS
        );

        assertThat(disabled.profiles()).isEmpty();
        assertThat(disabled.clinicalCases()).isEmpty();
    }

    private PatientServiceClient clientForThisServer(int readBudgetSeconds) {
        return new PatientServiceClient(
            RestClient.builder(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            true,
            PAGE_TIMEOUT_SECONDS,
            readBudgetSeconds
        );
    }

    /**
     * That one request carried {@code sort=id,asc} — the parameter no assertion used to name.
     *
     * <p>It is not decoration. Paging an unsorted Mongo query is how page 2 repeats or skips a row
     * from page 1, and the client's own javadoc argues exactly that; but while the assertions read
     * only {@code page=} and {@code size=}, the {@code sort} could have been deleted and the whole
     * suite would have stayed green.
     *
     * <p>Checked raw <em>and</em> decoded, because the comma is the fragile part: Spring's
     * {@code Pageable} resolver needs {@code id,asc} to arrive as one value with a comma in it, and a
     * double-encoded {@code %252C} would reach the sibling as the literal text {@code id%2Casc} and
     * sort by nothing.
     */
    private static void assertSortsByIdAscending(String query) {
        assertThat(query).as("every page request must carry a sort").contains("sort=");
        String raw = java.util.Arrays.stream(query.split("&"))
            .filter(pair -> pair.startsWith("sort="))
            .map(pair -> pair.substring("sort=".length()))
            .findFirst()
            .orElse("");
        assertThat(URLDecoder.decode(raw, StandardCharsets.UTF_8)).as("the comma must survive URI encoding").isEqualTo("id,asc");
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
        // Including the first request: an ordering that only appears from page 1 onwards would let
        // page 1 skip or repeat a row of page 0, which is the exact failure the sort exists to prevent.
        assertThat(queriesSeen).allSatisfy(PatientServiceClientPagingTest::assertSortsByIdAscending);
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
        serve(exchange -> Response.ok(jsonRows(IntStream.range(0, PatientServiceClient.PAGE_SIZE))));

        List<String> patientIds = clientForThisServer().activityLogs().stream().map(a -> a.patientId()).toList();

        assertThat(patientIds).hasSize(PatientServiceClient.PAGE_SIZE).doesNotHaveDuplicates();
        assertThat(queriesSeen).hasSize(2);
    }

    /**
     * The last line of defence: a sibling that answers every page with rows nobody has seen before
     * cannot hold a request thread for ever — and what it hands back is <b>empty</b>.
     *
     * <p>Unlike the case above there is no repetition to detect — every page is new and plausible — so
     * only the page cap can stop it. It is a guard rather than a limit anyone should reach, and it logs
     * at ERROR when it does.
     *
     * <p><b>Serving the twenty thousand rows it had collected was the first version, and it was wrong
     * in the way the client's own contract forbids.</b> Half a collection carries no signal — and this
     * half is the worst one: {@code sort=id,asc} over Mongo ObjectIds is approximately oldest-first, so
     * the rows kept are the oldest, and the consumer most likely to reach the ceiling is
     * {@code RosterTrailService.trailFor}, which reads all of {@code /api/activity-logs} to build a
     * last-N-days view. It would have been handed precisely the rows a trail cannot use and rendered a
     * quiet week: backlog item 22's third consequence, reproduced by item 22's fix.
     */
    @Test
    void theRunawayGuardFailsTheReadRatherThanServingATruncatedCollection() {
        AtomicInteger served = new AtomicInteger();
        serve(exchange -> {
            int offset = served.getAndIncrement() * PatientServiceClient.PAGE_SIZE;
            return Response.ok(jsonRows(IntStream.range(offset, offset + PatientServiceClient.PAGE_SIZE)));
        });

        PatientServiceClient client = clientForThisServer();
        assertThatThrownBy(client::medications)
            .isInstanceOf(PatientServiceUnavailableException.class)
            .hasMessageContaining("/api/medications")
            .hasMessageContaining("page guard");
        // It stopped at the guard rather than earlier or later; the read is bounded, it just refuses to
        // report the part of the collection it managed to see.
        assertThat(queriesSeen).hasSize(PatientServiceClient.MAX_PAGES);
    }

    /**
     * A sibling that answers, but slowly, cannot hold a request worker for the whole page budget.
     *
     * <p><b>Paging introduced this and the per-page timeout does not cover it.</b> {@code timeout-seconds}
     * bounds one request; multiplied by {@value PatientServiceClient#MAX_PAGES} it bounds a collection
     * at 500 seconds on the defaults, and {@code PatientDirectoryService.record()} makes five collection
     * reads inside one MVC request. This client's own javadoc promises that a hung sibling cannot hold
     * a worker thread open indefinitely, so the whole read carries a wall-clock deadline as well.
     *
     * <p>The fake sibling here is the runaway one slowed down: every page is full and every row is new,
     * so nothing but the deadline can stop it. Without the deadline this test runs for
     * {@value PatientServiceClient#MAX_PAGES} pages — twenty seconds at the delay below — and returns
     * twenty thousand rows instead of none.
     */
    @Test
    void aSlowSiblingIsStoppedByTheWholeCollectionBudget() {
        AtomicInteger served = new AtomicInteger();
        serve(exchange -> {
            try {
                // Comfortably under the per-page timeout: the point is that no single page is late,
                // only their sum.
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int offset = served.getAndIncrement() * PatientServiceClient.PAGE_SIZE;
            return Response.ok(jsonRows(IntStream.range(offset, offset + PatientServiceClient.PAGE_SIZE)));
        });

        PatientServiceClient client = clientForThisServer(1);
        long startedAt = System.nanoTime();
        assertThatThrownBy(client::reports)
            .as("an exhausted budget is a failure, and a failure is not an empty collection")
            .isInstanceOf(PatientServiceUnavailableException.class)
            .hasMessageContaining("budget");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(queriesSeen)
            .as("it gave up on the budget, not on the page guard")
            .hasSizeGreaterThan(1)
            .hasSizeLessThan(PatientServiceClient.MAX_PAGES);
        // Generous: the budget bounds the loop, not the request in flight, so one more page may be
        // served after the deadline passes. The assertion that matters is that it is bounded by the
        // budget and not by MAX_PAGES * the page timeout.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    }

    /**
     * A page that fails mid-collection raises; it neither truncates nor answers empty.
     *
     * <p>Truncating is the shape of item 22's very defect — every caller would filter a short list and
     * render it as a quiet week. Answering empty was item 22's fix and item 24's defect: it is a
     * perfectly ordinary value that four callers already had four readings of.
     */
    @Test
    void aFailedPageRaisesRatherThanTruncatingOrEmptying() {
        serve(
            exchange ->
                queriesSeen.size() == 1
                    ? Response.ok(jsonRows(IntStream.range(0, PatientServiceClient.PAGE_SIZE)))
                    : new Response(500, "{}")
        );

        PatientServiceClient client = clientForThisServer();
        assertThatThrownBy(client::reports).isInstanceOf(PatientServiceUnavailableException.class).hasMessageContaining("/api/reports");
        assertThat(statusesSent).containsExactly(200, 500);
    }

    /**
     * The other half of every case above: a collection that is genuinely empty is an empty list.
     *
     * <p>Without this the item-24 change could be satisfied by raising on everything, which would
     * merely move the conflation rather than remove it — {@code DutyRosterService} would then treat a
     * patient stack holding no profiles as an outage, and {@code PatientDirectoryService} would answer
     * 503 to a clinician whose caseload is legitimately empty on their first day.
     */
    @Test
    void anEmptyCollectionIsAnEmptyListAndNotAFailure() {
        sibling(0);

        assertThat(clientForThisServer().profiles()).isEmpty();
        assertThat(statusesSent).containsExactly(200);
    }

    /**
     * No credential to relay is a failed read, not an empty collection.
     *
     * <p>It answered empty until item 24, in precisely the situation where a wrong answer is most
     * plausible: this service could not relay the caller's token, so it knows nothing about their
     * caseload and must not report that as knowing it is empty. Nothing reaches the socket either —
     * reading with no credential would 401, or worse succeed against an endpoint that is open.
     */
    @Test
    void aReadWithNoCallerTokenRaisesWithoutCallingTheSibling() {
        sibling(COLLECTION_SIZE);
        SecurityContextHolder.clearContext();

        PatientServiceClient client = clientForThisServer();
        assertThatThrownBy(client::clinicalCases)
            .isInstanceOf(PatientServiceUnavailableException.class)
            .hasMessageContaining("no caller token");
        assertThat(queriesSeen).isEmpty();
    }

    // --- Scoping the read at the sibling (backlog.md item 23) ---------------------------------

    /**
     * A per-patient read asks the sibling to filter, on <b>every</b> page of it.
     *
     * <p><b>This has to be asserted on the request, and there is no second-best.</b> Filtering an
     * estate-wide collection in memory produces the identical list — that is precisely why the defect
     * survived item 22's review and why item 23 exists — so an assertion on the returned rows passes
     * against the unfixed client and proves nothing at all. What changed is what went over the wire.
     *
     * <p>The parameter is {@code patientId} because that is what hc-patient's five collection
     * resources declare: {@code @RequestParam(required = false) String patientId}, handed to
     * {@code PatientScope.findScopedPage}. Read from their source rather than from a comment on this
     * side, two of which asserted the opposite until 2026-09-10.
     */
    @Test
    void aPerPatientReadAsksTheSiblingToScopeTheQuery() {
        sibling(COLLECTION_SIZE);

        clientForThisServer().clinicalCases("patient-7");

        assertThat(queriesSeen).isNotEmpty();
        assertThat(queriesSeen).allSatisfy(query -> assertThat(query).contains("patientId=patient-7"));
        // Still paged, sorted and deduped like any other read: scoping changes which rows, not how
        // they are collected. A scoped read that stopped paging would be item 22 again, in one patient.
        assertThat(queriesSeen).allSatisfy(query -> assertThat(query).contains("page=").contains("size="));
        assertThat(queriesSeen).allSatisfy(PatientServiceClientPagingTest::assertSortsByIdAscending);
    }

    /**
     * The other half of it: an estate-wide read sends no {@code patientId} at all.
     *
     * <p>Paired with the case above for the reason every pair in this class exists. A client that
     * always sent the filter would satisfy that test and quietly break the three reads whose subject
     * is a whole caseload or a whole round — the caseload union, the directory's profiles, the roster
     * snapshots — which have no single patient to name and would be scoped to a null one.
     */
    @Test
    void anEstateWideReadSendsNoPatientIdAtAll() {
        sibling(COLLECTION_SIZE);

        clientForThisServer().clinicalCases();

        assertThat(queriesSeen).isNotEmpty();
        assertThat(queriesSeen).allSatisfy(query -> assertThat(query).doesNotContain("patientId"));
    }

    /**
     * A blank id is absent, not a filter for the empty-string patient.
     *
     * <p>{@code patientId=} would reach the sibling as a scope matching nothing, so a caller who had
     * lost track of which patient they meant would receive a confident empty collection instead of the
     * collection or an error. Empty is the one answer this client works hardest not to say by accident
     * (item 24), and this is the cheapest way to say it wrongly.
     */
    @Test
    void aBlankPatientIdIsTreatedAsNoFilterRatherThanAnImpossibleOne() {
        sibling(PatientServiceClient.PAGE_SIZE + 5);

        assertThat(clientForThisServer().activityLogs("   ")).hasSize(PatientServiceClient.PAGE_SIZE + 5);
        assertThat(queriesSeen).allSatisfy(query -> assertThat(query).doesNotContain("patientId"));
    }

    /**
     * A scoped read keeps every one of item 24's answers.
     *
     * <p>Both forms go through the same {@code getAll}, and this is what says so out loud: asking a
     * narrower question must not teach a caller a different vocabulary of answers. A failed scoped read
     * raises rather than answering empty, and a deployment with no sibling answers empty rather than
     * raising — the two boundaries item 24 spent an entry establishing for the no-argument form.
     */
    @Test
    void aScopedReadFailsAndDegradesExactlyAsAnEstateWideOneDoes() {
        serve(exchange -> new Response(500, "{}"));
        assertThatThrownBy(() -> clientForThisServer().reports("patient-7"))
            .isInstanceOf(PatientServiceUnavailableException.class)
            .hasMessageContaining("/api/reports");

        PatientServiceClient disabled = new PatientServiceClient(
            RestClient.builder(),
            "http://127.0.0.1:1",
            false,
            PAGE_TIMEOUT_SECONDS,
            READ_BUDGET_SECONDS
        );
        assertThat(disabled.medications("patient-7")).isEmpty();
    }

    /**
     * Every collection read pages, including the ones written after this test.
     *
     * <p><b>Derived, not enumerated.</b> The list of reads is taken from the class by reflection —
     * every public method that answers a {@link List} — so a read added tomorrow is covered tomorrow.
     * A test naming the five that exist today stops covering things the moment a sixth is written,
     * which is exactly how the vocabulary sweeps in this repo have gone wrong before (see backlog
     * item 8).
     *
     * <p><b>Parameters are not a reason to stop covering a method.</b> This filtered on
     * {@code getParameterCount() == 0} until 2026-09-02, which meant it would have silently dropped
     * {@code clinicalCases(String patientId)} on the day backlog item 23 added the sibling's
     * {@code patientId} filter — the sweep quietly narrowing to exclude precisely the methods about to
     * change. That day was 2026-09-10 and the five scoped reads it added are covered here without
     * anyone editing this test, which is the whole of the argument. Reference parameters are passed as
     * null, which for a filter means "no filter", so each scoped read is exercised in its unscoped
     * form; that it sends the filter when given one is asserted above.
     */
    @Test
    void everyCollectionReadAsksForAPage() throws Exception {
        sibling(PatientServiceClient.PAGE_SIZE + 5);
        PatientServiceClient client = clientForThisServer();

        List<Method> reads = java.util.Arrays.stream(PatientServiceClient.class.getDeclaredMethods())
            .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
            .filter(m -> List.class.equals(m.getReturnType()))
            .toList();

        assertThat(reads).as("the reflective sweep must match the collection reads that exist").isNotEmpty();
        for (Method read : reads) {
            queriesSeen.clear();
            assertThat((List<?>) read.invoke(client, absentArgumentsFor(read)))
                .as(read.getName())
                .hasSize(PatientServiceClient.PAGE_SIZE + 5);
            assertThat(queriesSeen).as(read.getName()).allSatisfy(query -> assertThat(query).contains("page=").contains("size="));
            assertThat(queriesSeen).as(read.getName()).allSatisfy(PatientServiceClientPagingTest::assertSortsByIdAscending);
        }
    }

    /**
     * "Nothing was asked for" in whatever types a read declares: null for a reference, a zero for a
     * primitive. A filter given null must read the whole collection, which is what the sweep asserts.
     */
    private static Object[] absentArgumentsFor(Method read) {
        return java.util.Arrays.stream(read.getParameterTypes()).map(PatientServiceClientPagingTest::absentValueFor).toArray();
    }

    private static Object absentValueFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        return switch (type.getName()) {
            case "boolean" -> Boolean.FALSE;
            case "long" -> 0L;
            case "double" -> 0d;
            case "float" -> 0f;
            case "char" -> (char) 0;
            default -> 0;
        };
    }
}
