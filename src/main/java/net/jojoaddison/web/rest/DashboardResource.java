package net.jojoaddison.web.rest;

import net.jojoaddison.service.PatientDirectoryService;
import net.jojoaddison.service.dto.PatientDtos.DashboardSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard figures professionalservice can answer on its own.
 *
 * <p><strong>Only one endpoint, and that is the design.</strong> The frontend's DashboardApiService
 * declares four — summary, case-timeline, case-distribution and case-by-patient-group — but three of
 * them are entirely case-derived, and cases belong to patientservice, which already serves them to
 * the browser at {@code /api/clinical-cases}. Those three are composed client-side rather than
 * proxied through here.
 *
 * <p>The alternative was to have this service fetch every case and aggregate them. That reads as
 * tidier from the browser's side and is worse everywhere else: it makes the dashboard unavailable
 * whenever patientservice is slow, duplicates aggregation logic that the chart code already has, and
 * puts this service in the position of publishing clinical counts it cannot verify.
 *
 * <p>So this returns the patient-shaped figures — how many patients the caller has, split by sex and
 * by child/adult — and returns no case fields at all rather than zeros. A dashboard tile reading
 * "0 urgent" is a clinical claim; an absent field is a missing panel.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardResource {

    private final PatientDirectoryService patientDirectoryService;

    public DashboardResource(PatientDirectoryService patientDirectoryService) {
        this.patientDirectoryService = patientDirectoryService;
    }

    /**
     * {@code GET /api/dashboard/summary} : patient counts for the calling clinician.
     *
     * <p><b>It answered 503 for a pharmacist and a chemist until 2026-09-14</b> (backlog item 112), over
     * {@code /api/activity-logs} — a collection none of these four figures is computed from. The counts
     * are now served, and they are the same counts an entitled caller gets, exactly:
     * {@link PatientDirectoryService#summary} tolerates that one refusal and still raises for the case
     * collection, which every figure here does depend on.
     *
     * <p><b>And it carries no {@code X-Restricted-Parts}, which is a decision rather than an omission.</b>
     * That header names a part the response was served <em>without</em>; nothing here is. Marking a
     * complete set of numbers as partial would be a false sentence pointing the other way, and a client
     * that rendered it would tell a clinician their dashboard is incomplete when it is whole. The
     * reasoning, and the argument against item 111's Decision C that lets the refusal through at all,
     * is on {@code PatientDirectoryService.countableCaseload()}.
     */
    @GetMapping("/summary")
    public DashboardSummary summary() {
        return patientDirectoryService.summary();
    }
}
