package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.service.PatientDirectoryService;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import net.jojoaddison.service.dto.PatientDtos.PatientRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
     * {@code GET /api/patients} : the caller's patient directory.
     *
     * <p>Carries {@code X-Total-Count} because the frontend requests this with
     * {@code observe: 'response'} and reads that header for paging. The list is not paged server-side
     * yet — a clinician's caseload is small, and paging a set assembled from two services is worth
     * doing once those services can page themselves (see MOB-P2-PRE). The header is honest today
     * because it equals the number of rows returned.
     */
    @GetMapping
    public ResponseEntity<List<PatientListItem>> list() {
        List<PatientListItem> patients = patientDirectoryService.directory();
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(patients.size())).body(patients);
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
