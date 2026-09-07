package net.jojoaddison.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.Medication;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientServiceRow;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads the patient-owned half of the clinician's world from patientservice.
 *
 * <p>professionalservice owns which patients a clinician has worked with — {@code Task.attendantId}
 * paired with {@code Task.patientId} — but owns almost nothing *about* those patients. Demographics,
 * clinical cases, activity logs, medications and reports all live in the sibling stack, and this
 * client is how they are reached.
 *
 * <p><strong>The caller's own token is relayed, never a service account.</strong> Every read is made
 * as the clinician who asked, so patientservice applies its own authorization rather than trusting
 * this service to have applied it. A service-wide credential here would quietly turn any
 * professionalservice endpoint into a way around the sibling's access rules.
 *
 * <p><strong>A failed read is reported as a failed read; what it <em>means</em> is the caller's.</strong>
 * The single-record and write methods below answer empty or propagate as their own javadoc says. The
 * collection reads raise {@link PatientServiceUnavailableException}, because "the collection is empty"
 * and "the collection could not be read" are different facts and the four consumers of these lists
 * mean genuinely different things by an empty one — see {@code getAll}. The timeouts remain short and
 * explicit: this runs inside a request, so a hung sibling must not hold a worker thread open
 * indefinitely.
 *
 * <p><strong>The read-many methods read the whole collection, over as many requests as that takes.</strong>
 * patientservice's generated endpoints all take a {@code Pageable}, and until 2026-09-02 this client
 * sent no {@code size} — so every one of them answered with Spring's default twenty rows and this
 * service filtered those twenty as if they were the collection. See {@code getAll} for the shape of
 * the fix and why it does not hardcode which endpoints page.
 *
 * <p><strong>Paging made one request into several, so the timeouts had to grow a second dimension.</strong>
 * {@code timeout-seconds} bounds one page; on its own it bounds a whole read at {@code MAX_PAGES ×}
 * that, which is 500 seconds at the defaults — and {@code PatientDirectoryService.record()} makes six
 * collection reads in one MVC request. {@code read-budget-seconds} is the wall-clock deadline for a
 * whole collection and is what keeps the promise made two paragraphs up. Exhausting it is a failure,
 * so it raises like any other.
 *
 * <p><strong>Failure is a much more frequent answer than it was, which is why it is now a
 * distinguishable one.</strong> A single request either worked or did not; a paged read of ~1260
 * clinical cases is seven requests at {@code PAGE_SIZE}, any one of which failing fails the whole
 * collection. The arithmetic runs the wrong way — more rows in the estate means more requests means
 * more chances to fail — which is one more reason the volume half (backlog item 23) matters, and why
 * a caller that read empty as "no such patient" rather than "no answer" was a defect rather than a
 * nuance. Since backlog item 24 that caller cannot make the mistake by accident: an unread collection
 * arrives as a {@link PatientServiceUnavailableException} and an empty one as an empty list.
 *
 * <p><strong>Known limit, and it is deliberate for now.</strong> Those endpoints offer no
 * clinician-scoped filter — no {@code assignedProfessionalId}, no set of patient ids — so a caseload
 * is still assembled by reading an estate-wide collection and narrowing it in memory. Paging makes
 * that correct and makes it more expensive, and cross-stack API design was not this fix's to do — so
 * the volume half is carried as backlog item 23 (see {@code docs/backlog.md}), which has an in-repo
 * half the sibling already supports (a single {@code patientId} filter, useful to the per-patient
 * reads) and a cross-stack half that needs a clinician-scoped endpoint over there.
 */
@Service
public class PatientServiceClient {

    private static final Logger LOG = LoggerFactory.getLogger(PatientServiceClient.class);

    /**
     * Rows asked for per request.
     *
     * <p><b>Not "all of them", and that is deliberate.</b> Spring clamps {@code size} above its
     * configured maximum, so asking for a million quietly becomes a page of two thousand — a request
     * for everything is therefore indistinguishable from a request for a page, and truncates in
     * exactly the way this client already truncated. Paging is the only shape that cannot lie about
     * being complete.
     *
     * <p>Package-private so the paging tests can derive their fixtures from it rather than restating
     * the number.
     */
    static final int PAGE_SIZE = 200;

    /**
     * Runaway guard: at {@link #PAGE_SIZE} rows a page, twenty thousand rows.
     *
     * <p>A limit nobody should reach, not a ceiling on the collection — reaching it is logged at ERROR
     * because it means this client is no longer reading everything, which is the defect it exists to
     * fix. The cheaper guard is in {@link #getAll}: a page that contributes no new row stops the read
     * immediately, so a sibling that ignores {@code page} costs one extra request rather than this
     * many.
     *
     * <p><b>Reaching it fails the read rather than serving what was collected</b> — and since backlog
     * item 24 "fails" means {@link PatientServiceUnavailableException} rather than an empty list. The first version
     * of this guard returned the rows it had, which is the one thing {@link #getAll}'s own contract
     * forbids — and it did it on the single path where the client <em>knows</em> the answer is
     * incomplete. It was also biased: {@code sort=id,asc} over Mongo ObjectIds is approximately
     * oldest-first, so the twenty thousand rows kept were the oldest, and the consumer most likely to
     * reach the ceiling is {@code RosterTrailService.trailFor}, which reads all of
     * {@code /api/activity-logs} to build a <em>last-N-days</em> view. At the ceiling that consumer
     * would receive exactly the rows a trail cannot use and render a quiet week — item 22's own third
     * consequence, reproduced by item 22's fix.
     */
    static final int MAX_PAGES = 100;

    private static final ParameterizedTypeReference<List<PatientProfile>> PROFILE_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<ClinicalCase>> CASE_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<ActivityLog>> ACTIVITY_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Medication>> MEDICATION_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Report>> REPORT_LIST = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final boolean enabled;
    private final Duration readBudget;

    public PatientServiceClient(
        RestClient.Builder builder,
        @Value("${application.patientservice.base-url:http://hc-patient-service:8081}") String baseUrl,
        @Value("${application.patientservice.enabled:true}") boolean enabled,
        @Value("${application.patientservice.timeout-seconds:5}") int timeoutSeconds,
        @Value("${application.patientservice.read-budget-seconds:20}") int readBudgetSeconds
    ) {
        this.enabled = enabled;
        // The whole-collection deadline. See getAll: `timeoutSeconds` bounds one page, this bounds
        // the loop over them, and without it paging turned a bounded read into an unbounded one.
        this.readBudget = Duration.ofSeconds(readBudgetSeconds);
        // JdkClientHttpRequestFactory, NOT SimpleClientHttpRequestFactory.
        //
        // Simple wraps java.net.HttpURLConnection, which does not support PATCH — it throws
        // `java.net.ProtocolException: Invalid HTTP method: PATCH` before a byte leaves the
        // process. Every read here is a GET and every write was a POST, so this was invisible
        // until `patchClinicalCase` was added: it shipped, every test passed, and editing a case
        // 500'd against the running stack. Nothing catches it below an integration test that makes
        // a real PATCH over a real transport, because the failure is in the JDK's HTTP client and
        // not in this code, this URL or the far service.
        //
        // The java.net.http client underneath this one has no such restriction. Do not swap it back
        // for Simple on the grounds that the reads do not need it.
        // BOTH timeouts, and they live in two different places on this factory — the connect
        // timeout on the HttpClient, the read timeout on the factory. Setting only the second is an
        // easy mistake when porting from Simple (which took both) and leaves the connect side
        // unbounded, which is half of the failure the timeouts exist to prevent: a sibling that
        // accepts nothing hangs a request thread just as effectively as one that answers nothing.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build()
        );
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        LOG.info(
            "patientservice client -> {} (enabled={}, timeout={}s per page, budget={}s per collection)",
            baseUrl,
            enabled,
            timeoutSeconds,
            readBudgetSeconds
        );
    }

    /**
     * Every patient profile. The join key is {@link PatientProfile#patientId()}, not the profile id.
     *
     * @throws PatientServiceUnavailableException if the collection could not be read. An empty list
     *     means the sibling holds no profiles, which is a different fact — see {@link #getAll}.
     */
    public List<PatientProfile> profiles() {
        return getAll("/api/profiles", PROFILE_LIST);
    }

    /**
     * The one profile belonging to an email address — the patient day plan's identity hop.
     *
     * <p><b>A single-record read, deliberately not {@link #profiles} filtered in memory.</b> That
     * one pages the whole collection; this answers one question about the caller, on the critical
     * path of a patient-facing request, and reading twelve hundred rows to find one is the shape
     * backlog item 23 is about. patientservice serves {@code /api/profiles/email/{email}} as the
     * dashboard's own entry point into the record, and scopes it on the <em>token's</em> email
     * rather than on the resolved patient id — so a patient may look themselves up and nobody else,
     * and this call inherits that rule rather than reimplementing it.
     *
     * <p><b>Empty on any failure, and the caller must read that as "cannot establish who is
     * asking".</b> {@code CustomerDayPlanService} turns it into a 403, which fails closed: an
     * unreachable patient stack refuses a day plan rather than serving somebody else's. That is the
     * one direction this may fail in.
     *
     * <p><b>Deliberately not changed by backlog item 24</b>, which gave the collection reads a
     * failure signal because four callers read their empty lists four different ways. This one has a
     * single caller and empty already fails closed there, so there is no ambiguity to resolve — and a
     * 503 would be a worse answer than the 403, which discloses nothing about whether the address
     * exists.
     */
    public Optional<PatientProfile> profileByEmail(String email) {
        if (!enabled || email == null || email.isBlank()) {
            return Optional.empty();
        }
        String token = SecurityUtils.getCurrentUserJWT().orElse(null);
        if (token == null) {
            LOG.warn("No caller token available; cannot resolve a patientservice profile by email");
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                restClient
                    .get()
                    .uri("/api/profiles/email/{email}", email)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(PatientProfile.class)
            );
        } catch (Exception e) {
            // The address itself is not logged: it identifies the caller, and this line is read out
            // of support tickets. A 404 arrives here too — patientservice answers one for a caller
            // asking about an address that is not their own, which is that endpoint's own way of
            // refusing without confirming the address exists.
            LOG.warn("patientservice profile lookup by email failed ({}); treating the caller as unresolved", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Every clinical case. Filter by {@code assignedProfessionalId} for a clinician's own caseload.
     *
     * @throws PatientServiceUnavailableException if the collection could not be read
     */
    public List<ClinicalCase> clinicalCases() {
        return getAll("/api/clinical-cases", CASE_LIST);
    }

    /** @throws PatientServiceUnavailableException if the collection could not be read */
    public List<ActivityLog> activityLogs() {
        return getAll("/api/activity-logs", ACTIVITY_LIST);
    }

    /** @throws PatientServiceUnavailableException if the collection could not be read */
    public List<Medication> medications() {
        return getAll("/api/medications", MEDICATION_LIST);
    }

    /** @throws PatientServiceUnavailableException if the collection could not be read */
    public List<Report> reports() {
        return getAll("/api/reports", REPORT_LIST);
    }

    /**
     * Files an activity-log entry against a patient, as the calling clinician.
     *
     * <p>patientservice stamps {@code createdBy} and {@code createdDate} from the token and applies
     * its own patient scope, so this cannot attribute an entry to someone else however the body is
     * built.
     */
    public ActivityLog createActivityLog(Map<String, Object> body) {
        return post("/api/activity-logs", body, ActivityLog.class);
    }

    /** Files a clinical report against a patient, as the calling clinician. */
    public Report createReport(Map<String, Object> body) {
        return post("/api/reports", body, Report.class);
    }

    /**
     * Partially updates a clinical case, as the calling clinician.
     *
     * <p>patientservice's PATCH requires the id in the body to match the one in the path — a JHipster
     * convention — so the caller must include it. Sent as {@code application/merge-patch+json}, which
     * is what that endpoint consumes.
     */
    public ClinicalCase patchClinicalCase(String id, Map<String, Object> body) {
        String path = "/api/clinical-cases/" + id;
        if (!enabled) {
            throw new IllegalStateException("patientservice is disabled; cannot update a case");
        }
        String token = SecurityUtils.getCurrentUserJWT()
            .orElseThrow(() -> new IllegalStateException("No caller token available; refusing to update a case"));
        try {
            return restClient
                .patch()
                .uri("/api/clinical-cases/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.valueOf("application/merge-patch+json"))
                .body(body)
                .retrieve()
                .body(ClinicalCase.class);
        } catch (RestClientException e) {
            // Scrubbed, not propagated: the raw message quotes the base URL. See failedWrite.
            throw failedWrite(path, e);
        }
    }

    /**
     * One POST, as the calling clinician.
     *
     * <p><b>Writes do not fail soft, and that is the whole point of a separate method.</b> A read
     * that did not happen raises {@link PatientServiceUnavailableException} and each caller decides
     * what to do about it — {@code DutyRosterService} keeps its stored snapshots, the rest let the
     * 503 travel. There is no such choice to offer here: telling a clinician their note was filed
     * when it does not exist is the one outcome a write must never produce. So a failure propagates,
     * the caller sees a 5xx (or the sibling's own 4xx, if it refused rather than failed) and — on a
     * phone — the offline queue keeps the entry and retries it.
     *
     * <p>This paragraph said "the reads above answer empty when patientservice is unreachable,
     * because a degraded dashboard beats a 500" until 2026-09-07. That stopped being true when item
     * 24 landed, thirty lines below a class javadoc that already said so — a comment that describes
     * behaviour the file no longer has is a defect, and this one was in the file that change edited.
     */
    private <T> T post(String path, Map<String, Object> body, Class<T> type) {
        if (!enabled) {
            throw new IllegalStateException("patientservice is disabled; cannot write " + path);
        }
        String token = SecurityUtils.getCurrentUserJWT()
            .orElseThrow(() -> new IllegalStateException("No caller token available; refusing to write " + path));
        try {
            return restClient
                .post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(type);
        } catch (RestClientException e) {
            throw failedWrite(path, e);
        }
    }

    /**
     * A whole collection, as the calling clinician, raising on any failure.
     *
     * <h3>Why this pages, and why it does not simply ask for everything</h3>
     * Every collection endpoint in patientservice takes a {@code Pageable}, so one asked without a
     * {@code size} answers with <b>twenty rows</b> — and this method used to send none. The rows were
     * then filtered in memory and served as a clinician's whole caseload: a doctor with a hundred
     * patients saw nineteen, nineteen being how many of the first twenty rows happened to be theirs.
     * Asking for one enormous page is not the fix, because Spring clamps {@code size} above its
     * configured maximum and hands back a smaller page without saying so.
     *
     * <h3>It does not encode which endpoints page</h3>
     * <b>Deliberately, because that answer goes stale.</b> {@code quality/seed-data.py} kept a set of
     * endpoints known not to paginate and it was wrong within a fortnight of being written — backlog
     * item 7 (see {@code docs/backlog.md}) gave {@code /api/duty-roster/all} a {@code Pageable} and
     * the stale constant silently broke that script's idempotency guard. So this loop asks the
     * collection how it behaves instead:
     *
     * <ul>
     *   <li>a <b>short or empty page</b> is the last one, which is how a paging endpoint ends;
     *   <li>a page contributing <b>no row this read has not already seen</b> stops it too, which is how
     *       an endpoint that ignores {@code page} ends — it hands back the same rows for ever, and a
     *       pager that trusted the parameter would append the collection to itself until the guard
     *       below tripped;
     *   <li>{@link #MAX_PAGES} is the backstop for the case neither of those catches.
     * </ul>
     *
     * <p>The rows accumulate in a {@link LinkedHashMap} keyed on {@link PatientServiceRow#id()}, so
     * that second rule costs nothing extra: the dedupe <em>is</em> the progress check, and overlapping
     * pages — the ordinary consequence of a concurrent write during a multi-page read — cannot deliver
     * a row twice.
     *
     * <p><b>Keyed on the id, and the id is compile-checked.</b> The obvious implementation is a
     * {@link LinkedHashSet} of records, which dedupes by value equality — and that is correct only
     * while every projection carries a populated {@code id}, which nothing would have asserted. These
     * DTOs are deliberately partial and are edited when a field goes unused ({@code ActivityLog}'s
     * javadoc records exactly such a rewrite). {@code Medication} and {@code Report} are the exposed
     * cases: two rows of <em>Paracetamol 500mg / ACTIVE / same patient / same startedOn</em> differ
     * only by their ids. Dropping {@code id} from one record for tidiness would then fail twice and
     * silently — the duplicate row would be dropped, <em>and</em> the page would look like it
     * contributed nothing new, ending the read early and blaming a sibling that ignores {@code page}.
     * Data loss reported as a paging diagnosis. {@link PatientServiceRow} is a sealed interface with a
     * {@code String id()}, so removing the component stops the build instead.
     *
     * <p><b>{@code sort=id,asc} for the same reason it is not left to the caller elsewhere.</b> Paging
     * an unsorted query is how page 2 silently repeats or skips a row from page 1; Mongo promises no
     * order across separate queries. Every document in the sibling carries an {@code id}, so this is
     * the one key guaranteed to exist and to be unique.
     *
     * <p><b>A whole collection has a wall-clock budget, not only a per-page timeout.</b> The request
     * factory's timeout bounds one page. Before this budget existed the only bound on a whole read was
     * {@link #MAX_PAGES} multiplied by it — 500 seconds at the defaults, per collection, and
     * {@code PatientDirectoryService.record()} makes six collection reads in one MVC request. That is
     * an availability regression against the promise in this class's own javadoc, so the deadline is
     * taken once at the top and checked before each further page. It bounds the loop rather than the
     * request in flight, so the true ceiling is the budget plus one page timeout.
     *
     * <p><b>A failure mid-collection raises, it does not truncate</b> — and every stop that is not an
     * ordinary end of collection is a failure, including the deadline and the page guard. Half a
     * collection carries no signal at all: it is the exact shape of the defect above, and every caller
     * would filter it and render the remainder as a quiet week. <b>Nor is empty the answer any more.</b>
     * It was, and it was right for the two callers that read it as an outage — {@code DutyRosterService}
     * keeps its stored customer snapshots rather than blanking every name and address on a roster — but
     * the same empty list told {@code PatientDirectoryService} that the patient a clinician was standing
     * next to <em>was not in their caseload</em>. One value cannot carry both meanings, so this method
     * stops choosing between them: {@link PatientServiceUnavailableException} for a read that did not
     * happen, an empty list only for a collection that is genuinely empty, and the decision about what
     * that means belongs to each caller. Backlog item 24.
     *
     * <p><b>Two non-failures that still answer empty, and they are not the same as each other.</b>
     * {@code enabled=false} is a deployment in which patientservice is deliberately absent — the same
     * statement {@code application.kafka.enabled=false} makes about the broker — so its collections are
     * empty rather than unreadable, and a 503 there would be a lie about a supported configuration. A
     * missing caller token is the opposite: it means this read <em>did not happen</em>, so it raises
     * like any other failure. Answering empty for it was the same conflation as answering empty for an
     * outage, and it is reachable in exactly the situation where a wrong answer is most plausible —
     * a caller whose credential this service could not relay.
     */
    private <T extends PatientServiceRow> List<T> getAll(String path, ParameterizedTypeReference<List<T>> type) {
        if (!enabled) {
            return List.of();
        }
        String token = SecurityUtils.getCurrentUserJWT().orElse(null);
        if (token == null) {
            // No credential to relay. Reading with none would either 401 or, worse, succeed against
            // an endpoint that is open — returning data the caller was never authorised for.
            LOG.warn("No caller token available; cannot read {} from patientservice", path);
            throw PatientServiceUnavailableException.read(path, PatientServiceUnavailableException.Fault.NO_TOKEN, null);
        }
        Map<Object, T> rows = new LinkedHashMap<>();
        Instant deadline = Instant.now().plus(readBudget);
        boolean loggedMissingId = false;
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                if (page > 0 && !Instant.now().isBefore(deadline)) {
                    // Thrown rather than returned, so it takes the same path as any other failure and
                    // reaches the caller as an outage. A slow sibling that hands back half a caseload
                    // is the shape of the defect this whole method exists to fix.
                    throw PatientServiceUnavailableException.read(
                        path,
                        PatientServiceUnavailableException.Fault.BUDGET_EXHAUSTED,
                        "%ds, after %d page(s) and %d row(s)".formatted(readBudget.toSeconds(), page, rows.size())
                    );
                }
                List<T> batch = getPage(path, type, token, page);
                if (batch == null || batch.isEmpty()) {
                    return List.copyOf(rows.values());
                }
                int before = rows.size();
                for (T row : batch) {
                    Object key = row.id();
                    if (key == null) {
                        if (!loggedMissingId) {
                            LOG.warn(
                                "patientservice returned a row of {} with no id; keeping it by identity, which cannot recognise a repeat",
                                path
                            );
                            loggedMissingId = true;
                        }
                        // A fresh key per such row: keep it rather than drop it. It cannot register as
                        // "already seen", so a sibling that ignores `page` falls to the deadline or
                        // the page guard instead of stopping cheaply — the safe direction of the two.
                        key = new Object();
                    }
                    rows.putIfAbsent(key, row);
                }
                if (rows.size() == before) {
                    LOG.warn(
                        "patientservice returned page {} of {} with no row this read had not already seen; either it does not page, " +
                        "or the collection shrank under the read. Stopping at {} row(s) rather than reading the same page for ever",
                        page,
                        path,
                        rows.size()
                    );
                    return List.copyOf(rows.values());
                }
                if (batch.size() < PAGE_SIZE) {
                    return List.copyOf(rows.values());
                }
            }
            LOG.error(
                "patientservice read of {} hit the {}-page guard at {} row(s); the collection is larger than this client will read, " +
                "so the read is failed rather than serving the oldest {} rows as if they were the collection",
                path,
                MAX_PAGES,
                rows.size(),
                rows.size()
            );
            throw PatientServiceUnavailableException.read(
                path,
                PatientServiceUnavailableException.Fault.PAGE_GUARD,
                "%d pages, at %d row(s)".formatted(MAX_PAGES, rows.size())
            );
        } catch (PatientServiceUnavailableException e) {
            // Raised by this method itself — the read budget or the page guard — with a message this
            // service wrote. Rethrown unchanged so that reason survives rather than being flattened
            // into the generic one below.
            throw e;
        } catch (Exception e) {
            throw failedRead(path, rows.size(), e);
        }
    }

    /**
     * The end of a read that did not happen: logged with its throwable, raised without it.
     *
     * <p><b>The catch above stays broad, and this method is why that is now safe to say out loud.</b>
     * A call that failed for a reason nobody anticipated is still a call that failed, and answering
     * empty for it is the conflation item 24 removed — so narrowing the catch to the transport
     * exceptions would put an unanticipated failure back on the "empty collection" path. What breadth
     * costs is that a <b>schema</b> fault arrives here too: one row whose {@code loggedAt} the sibling
     * has changed the type of fails Jackson, and every clinician surface computed from that collection
     * then answers 503 <em>persistently</em>, where a transport outage clears itself. That drift has
     * happened once already ({@code PatientServiceDtos.ActivityLog}, corrected 2026-08-22).
     *
     * <p><b>Both are still 503</b> — an empty record is a worse answer to a clinician than an error,
     * whichever the cause — but they are told apart, because the remedies are opposites: waiting is
     * right for one and useless for the other. {@link PatientServiceUnavailableException.Fault} names
     * which, in the message and so in the problem detail, and the log level follows it: ERROR for a
     * fault that will not clear on its own, WARN for one that may.
     *
     * <p>The throwable is logged rather than carried, and the detail is the failure's class name
     * rather than its message, for the same single reason: a {@code RestClient} message quotes the URL
     * it called, internal base URL included, and {@code ExceptionTranslator} prefers a cause's message
     * over the exception's own when it builds the problem detail.
     */
    private PatientServiceUnavailableException failedRead(String path, int rowsSeen, Exception failure) {
        PatientServiceUnavailableException.Fault fault = PatientServiceUnavailableException.Fault.of(failure);
        if (fault.clearsOnRetry()) {
            LOG.warn(
                "patientservice read of {} failed after {} row(s) [{}]; the caller decides what that means",
                path,
                rowsSeen,
                fault,
                failure
            );
        } else {
            LOG.error(
                "patientservice read of {} failed after {} row(s) [{}: {}]. This does NOT clear itself: every read of this " +
                "collection will fail the same way until a DTO in PatientServiceDtos or the sibling's schema changes. " +
                "Retrying, restarting and waiting are all the wrong remedy",
                path,
                rowsSeen,
                fault,
                fault.description(),
                failure
            );
        }
        return PatientServiceUnavailableException.read(path, fault, failure.getClass().getSimpleName());
    }

    /**
     * The end of a write that did not happen. Same no-cause rule as {@link #failedRead}, and the same
     * reason for it — this one just took three weeks longer to apply.
     *
     * <p><b>The leak was here, not on the reads</b> (found by the review of item 24, 2026-09-07).
     * Both writes propagated the raw {@code RestClientException}, whose message is
     * {@code I/O error on POST request for "http://hc-patient-service:8081/api/reports": hc-patient-service:
     * Name or service not known}. {@code ExceptionTranslator} takes the <em>cause's</em> message in
     * preference to the exception's own and, in {@code prod}, scrubs it only if
     * {@code containsPackageName} matches — which looks for {@code org. java. net. com. io. de.} and
     * finds none of them in a hostname. So the sibling's internal name reached a public 500 body,
     * reachable whenever the reads succeed and the write does not: a partial outage, a POST timing out
     * under load, DNS flapping between the six reads and the write.
     *
     * <p><b>A refusal is passed through with its status, and that is the one asymmetry with the
     * reads.</b> A 4xx means the sibling <em>answered</em> — it read the request and would not have
     * it — and reporting that as 503 would be a second conflation of exactly item 24's kind, one
     * layer out: {@code mobile/}'s offline queue classifies 4xx as {@code rejected} and 5xx as
     * {@code retry} ({@code queued-write.model.ts}), so a permanently malformed entry reported as 503
     * would be retried for ever. The status travels; the body does not, because a
     * {@code ResponseStatusException} built with a reason and no cause cannot carry one.
     *
     * <p><b>401 is the exception to that exception.</b> It does not mean the caller is unauthenticated
     * here — this service already authenticated them — it means the token this service relayed was not
     * accepted over there, which in practice is the shared signing key having drifted between the
     * stacks (see {@code docs/CLAUDE.md} § the JWT secret). Passing it through would sign a clinician
     * out of a portal that is working, so it is reported as what it is: the sibling being unusable.
     */
    private RuntimeException failedWrite(String path, RestClientException failure) {
        PatientServiceUnavailableException.Fault fault = PatientServiceUnavailableException.Fault.of(failure);
        if (fault.clearsOnRetry()) {
            LOG.warn("patientservice write to {} failed [{}]", path, fault, failure);
        } else {
            LOG.error("patientservice write to {} failed [{}: {}]; this does NOT clear itself", path, fault, fault.description(), failure);
        }
        if (failure instanceof RestClientResponseException answered && answered.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = answered.getStatusCode();
            if (!HttpStatus.UNAUTHORIZED.isSameCodeAs(status)) {
                return new ResponseStatusException(status, "patientservice refused the write to " + path);
            }
        }
        return PatientServiceUnavailableException.write(path, fault, failure.getClass().getSimpleName());
    }

    /**
     * One page of one collection. Throws on failure — {@link #getAll} decides what a failure means.
     *
     * <p>All three query parameters are asserted by {@code PatientServiceClientPagingTest}, {@code sort}
     * included and on every request: it is the parameter that stops page 2 repeating page 1, and it was
     * deletable without turning a test red for as long as only {@code page} and {@code size} were
     * checked.
     */
    private <T> List<T> getPage(String path, ParameterizedTypeReference<List<T>> type, String token, int page) {
        return restClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder.path(path).queryParam("page", page).queryParam("size", PAGE_SIZE).queryParam("sort", "id,asc").build()
            )
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(type);
    }
}
