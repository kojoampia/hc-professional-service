package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.service.PatientDirectoryService;
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
 * <p>Reads only. Creating patients, cases or clinical records belongs to patientservice, and adding
 * write endpoints here would put two services in charge of the same documents.
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
