package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.service.PatientDirectoryService;
import net.jojoaddison.service.dto.PatientDtos.ActivityLogEntry;
import net.jojoaddison.service.dto.PatientDtos.ClinicalReport;
import net.jojoaddison.service.dto.PatientDtos.CreateActivity;
import net.jojoaddison.service.dto.PatientDtos.CreateReport;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import net.jojoaddison.service.dto.PatientDtos.PatientRecord;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.PaginationUtil;

/**
 * The calling clinician's patients — those they have worked with or are working with now.
 *
 * <p>This is a professional-scoped view, not a patient registry. professionalservice owns the
 * relation (which clinician is attached to which patient); patientservice owns the people. The list
 * is therefore always "my patients" and never "all patients", and there is deliberately no way to
 * ask for someone else's.
 *
 * <p><b>Reads, plus two writes that forward rather than own.</b> Creating patients and cases still
 * belongs to patientservice. The two POSTs below file an activity entry and a report against a
 * patient the caller already has, and they do it by relaying the caller's own token to
 * patientservice — this service adds the entitlement check its sibling cannot make, and stores
 * nothing of the record but an idempotency receipt.
 */
@RestController
@RequestMapping("/api/patients")
public class PatientResource {

    private final PatientDirectoryService patientDirectoryService;

    public PatientResource(PatientDirectoryService patientDirectoryService) {
        this.patientDirectoryService = patientDirectoryService;
    }

    /**
     * {@code GET /api/patients} : one page of the caller's patient directory.
     *
     * <p><b>Paged since 2026-08-22</b> (web-mobile-port.md § Phase 1.1). It previously accepted no
     * parameters at all and answered with the whole caseload, and its {@code X-Total-Count} was
     * {@code list.size()} — a header that agreed with the body by construction and so could never
     * tell a client there was more. It now carries the number of <em>matches</em>, alongside the
     * {@code Link} header, exactly as {@code ProfileResource} does.
     *
     * <p>The trade this makes explicit: a phone on mobile data receives twenty rows rather than a
     * whole caseload. {@code web/} already asks for {@code size=200} and filters client-side, so it
     * is unaffected; a caller that sends no paging parameters now receives Spring's default page
     * rather than everything, which is the point.
     *
     * @param pageable standard Spring Data paging. Sorts are whitelisted — see
     *     {@link PatientDirectoryService#sortableProperties()} — because the list is assembled in
     *     memory and a sort is a comparator lookup rather than an index.
     * @param query matched against the patient's name <em>and</em> id, case-insensitively
     * @param sex {@code female} / {@code male} / {@code unspecified}, matching the DTO's closed set
     * @param childrenOnly restricts to patients under 18, computed per read from the birth date
     */
    @GetMapping
    public ResponseEntity<List<PatientListItem>> list(
        @ParameterObject Pageable pageable,
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String sex,
        @RequestParam(required = false) Boolean childrenOnly
    ) {
        Page<PatientListItem> page;
        try {
            page = patientDirectoryService.directory(pageable, new PatientDirectoryService.DirectoryFilter(query, sex, childrenOnly));
        } catch (IllegalArgumentException e) {
            // An unsortable property. 400 rather than a silently unsorted page: the latter looks like
            // a backend that lost the clinician's ordering, and nobody reports that as a bug.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }

    /**
     * {@code POST /api/patients/{id}/activities} : file an activity-log entry.
     *
     * <p><b>New in Phase 1.3, and not a port.</b> {@code web/} has POSTed here since the dashboard
     * was built; the endpoint never existed, so every "add activity" returned 404. See
     * {@code web-mobile-port.md}.
     *
     * <p><b>Left outside the hoisted prefixes on purpose.</b> {@code /api/patients/**} is not listed
     * above the {@code POST /api/**} rule in {@link net.jojoaddison.config.SecurityConfiguration},
     * so this requires {@code CLINICAL_MUTATION} — carer, angel, chemist and technician read a record
     * and cannot file into one. The four prefixes that <em>are</em> hoisted (onboarding, messaging,
     * notifications, absences) were each hoisted to escape that rule, and will look like precedent
     * to the next person. They are not: filing a clinical observation is exactly what the rule is for.
     *
     * <p>404 rather than 403 for a patient outside the caller's caseload, matching the GET: a
     * clinician must not learn that a patient id is real by trying to write to it.
     */
    @PostMapping("/{id}/activities")
    public ResponseEntity<ActivityLogEntry> appendActivity(@PathVariable String id, @RequestBody CreateActivity request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(patientDirectoryService.appendActivity(id, request));
        } catch (PatientDirectoryService.PatientNotInCaseloadException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patient for this clinician", e);
        } catch (IllegalArgumentException e) {
            // A clientRef already spent on a different write. 409 rather than 400: the request is
            // well-formed, it simply cannot be honoured twice.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * {@code POST /api/patients/{id}/reports} : file a clinical report.
     *
     * <p>Same authorization and the same 404 rule as {@link #appendActivity}.
     *
     * <p>This carries <b>metadata only</b> — a name, a category, a URL. It is not a file upload, and
     * the dashboard's "upload" control on the patient record never moved any bytes either; it sent
     * the filename as a URL. Attaching real content to a patient record is a separate feature and is
     * deliberately not pretended at here.
     */
    @PostMapping("/{id}/reports")
    public ResponseEntity<ClinicalReport> appendReport(@PathVariable String id, @RequestBody CreateReport request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(patientDirectoryService.appendReport(id, request));
        } catch (PatientDirectoryService.PatientNotInCaseloadException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patient for this clinician", e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * {@code GET /api/patients/{id}} : one patient's record.
     *
     * <p>404 when the patient is not one of the caller's, which is the same answer as for a patient
     * that does not exist. Distinguishing the two would let any clinician test whether a given
     * patient id is real.
     */
    @GetMapping("/{id}")
    public PatientRecord get(@PathVariable String id) {
        return patientDirectoryService
            .record(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patient for this clinician"));
    }
}
