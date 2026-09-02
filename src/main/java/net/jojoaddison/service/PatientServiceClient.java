package net.jojoaddison.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.Medication;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
 * <p><strong>Failure degrades, it does not propagate.</strong> Every method answers with an empty
 * result when patientservice is unreachable, slow or unhappy, and logs it. The dashboard then renders
 * empty panels — the same thing it does when an endpoint has not been built — rather than returning
 * 500 for a page the clinician could have partially used. The timeouts are short and explicit for the
 * same reason: this runs inside a request, so a hung sibling must not hold a worker thread open
 * indefinitely.
 *
 * <p><strong>The read-many methods read the whole collection, over as many requests as that takes.</strong>
 * patientservice's generated endpoints all take a {@code Pageable}, and until 2026-09-02 this client
 * sent no {@code size} — so every one of them answered with Spring's default twenty rows and this
 * service filtered those twenty as if they were the collection. See {@code getAll} for the shape of
 * the fix and why it does not hardcode which endpoints page.
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
     */
    static final int MAX_PAGES = 100;

    private static final ParameterizedTypeReference<List<PatientProfile>> PROFILE_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<ClinicalCase>> CASE_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<ActivityLog>> ACTIVITY_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Medication>> MEDICATION_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Report>> REPORT_LIST = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final boolean enabled;

    public PatientServiceClient(
        RestClient.Builder builder,
        @Value("${application.patientservice.base-url:http://hc-patient-service:8081}") String baseUrl,
        @Value("${application.patientservice.enabled:true}") boolean enabled,
        @Value("${application.patientservice.timeout-seconds:5}") int timeoutSeconds
    ) {
        this.enabled = enabled;
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
        LOG.info("patientservice client -> {} (enabled={}, timeout={}s)", baseUrl, enabled, timeoutSeconds);
    }

    /** Every patient profile. The join key is {@link PatientProfile#patientId()}, not the profile id. */
    public List<PatientProfile> profiles() {
        return getAll("/api/profiles", PROFILE_LIST);
    }

    /** Every clinical case. Filter by {@code assignedProfessionalId} for a clinician's own caseload. */
    public List<ClinicalCase> clinicalCases() {
        return getAll("/api/clinical-cases", CASE_LIST);
    }

    public List<ActivityLog> activityLogs() {
        return getAll("/api/activity-logs", ACTIVITY_LIST);
    }

    public List<Medication> medications() {
        return getAll("/api/medications", MEDICATION_LIST);
    }

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
        if (!enabled) {
            throw new IllegalStateException("patientservice is disabled; cannot update a case");
        }
        String token = SecurityUtils.getCurrentUserJWT()
            .orElseThrow(() -> new IllegalStateException("No caller token available; refusing to update a case"));
        return restClient
            .patch()
            .uri("/api/clinical-cases/{id}", id)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.valueOf("application/merge-patch+json"))
            .body(body)
            .retrieve()
            .body(ClinicalCase.class);
    }

    /**
     * One POST, as the calling clinician.
     *
     * <p><b>Writes do not fail soft, and that is the whole point of a separate method.</b> The reads
     * above answer empty when patientservice is unreachable, because a degraded dashboard beats a
     * 500 on a page the clinician could partly use. Applying that to a write would be the opposite
     * of kind: the clinician would be told their note was filed, and it would not exist. A failure
     * here propagates so the caller sees a 5xx and — on a phone — the offline queue keeps the entry
     * and retries it.
     */
    private <T> T post(String path, Map<String, Object> body, Class<T> type) {
        if (!enabled) {
            throw new IllegalStateException("patientservice is disabled; cannot write " + path);
        }
        String token = SecurityUtils.getCurrentUserJWT()
            .orElseThrow(() -> new IllegalStateException("No caller token available; refusing to write " + path));
        return restClient
            .post()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(type);
    }

    /**
     * A whole collection, as the calling clinician, answering empty on any failure.
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
     * <p>The rows accumulate in a {@link LinkedHashSet}, so that second rule costs nothing extra: the
     * dedupe <em>is</em> the progress check, and overlapping pages — the ordinary consequence of a
     * concurrent write during a multi-page read — cannot deliver a row twice.
     *
     * <p><b>{@code sort=id,asc} for the same reason it is not left to the caller elsewhere.</b> Paging
     * an unsorted query is how page 2 silently repeats or skips a row from page 1; Mongo promises no
     * order across separate queries. Every document in the sibling carries an {@code id}, so this is
     * the one key guaranteed to exist and to be unique.
     *
     * <p><b>A failure mid-collection answers empty, not truncated.</b> Empty is a signal this codebase
     * already reads — {@code DutyRosterService.refreshSnapshots} treats an empty profile list as an
     * outage and keeps the stored snapshots rather than blanking them. Half a collection carries no
     * such signal: it is the exact shape of the defect above, and every caller would filter it and
     * render the remainder as a quiet week.
     */
    private <T> List<T> getAll(String path, ParameterizedTypeReference<List<T>> type) {
        if (!enabled) {
            return List.of();
        }
        String token = SecurityUtils.getCurrentUserJWT().orElse(null);
        if (token == null) {
            // No credential to relay. Reading with none would either 401 or, worse, succeed against
            // an endpoint that is open — returning data the caller was never authorised for.
            LOG.warn("No caller token available; skipping patientservice read of {}", path);
            return List.of();
        }
        Set<T> rows = new LinkedHashSet<>();
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                List<T> batch = getPage(path, type, token, page);
                if (batch == null || batch.isEmpty()) {
                    return List.copyOf(rows);
                }
                int before = rows.size();
                rows.addAll(batch);
                if (rows.size() == before) {
                    LOG.warn(
                        "patientservice returned page {} of {} with no row this read had not already seen; it appears not to page. " +
                        "Stopping at {} row(s) rather than reading the same page for ever",
                        page,
                        path,
                        rows.size()
                    );
                    return List.copyOf(rows);
                }
                if (batch.size() < PAGE_SIZE) {
                    return List.copyOf(rows);
                }
            }
            LOG.error(
                "patientservice read of {} hit the {}-page guard at {} row(s); the collection is larger than this client will read " +
                "and the caller is being served an incomplete answer",
                path,
                MAX_PAGES,
                rows.size()
            );
            return List.copyOf(rows);
        } catch (Exception e) {
            LOG.warn("patientservice read of {} failed after {} row(s) ({}); returning empty", path, rows.size(), e.getMessage());
            return List.of();
        }
    }

    /**
     * One page of one collection. Throws on failure — {@link #getAll} decides what a failure means.
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
