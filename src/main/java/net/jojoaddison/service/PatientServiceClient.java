package net.jojoaddison.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 * <p><strong>Known limit, and it is deliberate for now.</strong> patientservice's generated
 * collection endpoints take no filter parameters, so the read-many methods fetch a collection and
 * this service filters in memory. That is fine at the current data volume and is exactly the problem
 * MOB-P2-PRE describes (see {@code docs/mobile-app-plan.md}); when those endpoints learn to page and
 * filter, the filtering below should move into the query rather than growing a cache here.
 */
@Service
public class PatientServiceClient {

    private static final Logger LOG = LoggerFactory.getLogger(PatientServiceClient.class);

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
        // Explicit connect and read timeouts. The default is no timeout at all, which inside a
        // request thread means a sibling that stops answering takes this service down with it.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        LOG.info("patientservice client -> {} (enabled={}, timeout={}s)", baseUrl, enabled, timeoutSeconds);
    }

    /** Every patient profile. The join key is {@link PatientProfile#patientId()}, not the profile id. */
    public List<PatientProfile> profiles() {
        return get("/api/profiles", PROFILE_LIST);
    }

    /** Every clinical case. Filter by {@code assignedProfessionalId} for a clinician's own caseload. */
    public List<ClinicalCase> clinicalCases() {
        return get("/api/clinical-cases", CASE_LIST);
    }

    public List<ActivityLog> activityLogs() {
        return get("/api/activity-logs", ACTIVITY_LIST);
    }

    public List<Medication> medications() {
        return get("/api/medications", MEDICATION_LIST);
    }

    public List<Report> reports() {
        return get("/api/reports", REPORT_LIST);
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
     * One GET, as the calling clinician, answering empty on any failure.
     *
     * <p>The exception is swallowed by design — see the class javadoc. It is logged at WARN with the
     * path so an operator can tell "patientservice is down" from "that collection is genuinely
     * empty", which are indistinguishable from the response alone.
     */
    private <T> List<T> get(String path, ParameterizedTypeReference<List<T>> type) {
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
        try {
            List<T> body = restClient.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(type);
            return body == null ? List.of() : body;
        } catch (Exception e) {
            LOG.warn("patientservice read of {} failed ({}); returning empty", path, e.getMessage());
            return List.of();
        }
    }
}
