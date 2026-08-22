package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.service.PatientDirectoryService;
import net.jojoaddison.service.dto.PatientDtos.CaseSummary;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.PaginationUtil;

/**
 * The calling clinician's own case queue.
 *
 * <p><b>Why this is not just a call to patientservice.</b> Its {@code /api/clinical-cases} is
 * generated CRUD: no filters, no paging, and — the part that matters — no clinician scope. A client
 * calling it receives every clinical case in the estate and narrows the list in the browser, which
 * is how the web dashboard works today. That is tolerable behind a desktop session and is not
 * something to ship to a phone, both because of what it downloads and because of what it exposes.
 *
 * <p>So the caseload is resolved here, from {@code ClinicalCase.assignedProfessionalId} against the
 * caller's own profile, and only their cases leave this service.
 *
 * <p>Reads only. Editing a case goes through {@code PatientResource}, where the patient in the path
 * gives the entitlement check something to check against.
 */
@RestController
@RequestMapping("/api/cases")
public class CaseQueueResource {

    private final PatientDirectoryService patientDirectoryService;

    public CaseQueueResource(PatientDirectoryService patientDirectoryService) {
        this.patientDirectoryService = patientDirectoryService;
    }

    /**
     * {@code GET /api/cases} : the caller's own open cases, newest first.
     *
     * <p>Archived cases are excluded, matching the sibling's default — an archived case is one
     * retired from the working queue, and this is the working queue.
     *
     * @param status optional, case-insensitive, matched against the case's own status
     */
    @GetMapping
    public ResponseEntity<List<CaseSummary>> myCases(@ParameterObject Pageable pageable, @RequestParam(required = false) String status) {
        Page<CaseSummary> page = patientDirectoryService.myCases(pageable, status);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }
}
