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

    /** {@code GET /api/dashboard/summary} : patient counts for the calling clinician. */
    @GetMapping("/summary")
    public DashboardSummary summary() {
        return patientDirectoryService.summary();
    }
}
