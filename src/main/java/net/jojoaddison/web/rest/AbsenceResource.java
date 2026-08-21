package net.jojoaddison.web.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.jojoaddison.domain.Absence;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.AbsenceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Time off (docs/duty-roster.md § 8, DR4): professionals request, roster administrators grant.
 *
 * <p>The path shape follows the duty roster's, so the two read alike: the <b>bare {@code GET} is your
 * own</b> and {@code /all} is the administrator's estate view. Getting that inverted here would
 * publish every clinician's sick leave to everyone signed in.
 *
 * <p><b>This resource is registered in {@code SecurityConfiguration} as {@code .authenticated()}
 * rather than falling through to the {@code CLINICAL_MUTATION} matchers, and it has to be.</b> Those
 * matchers require {@code POST}/{@code PUT}/{@code DELETE} on {@code /api/**} to carry one of admin,
 * doctor, nurse, paramedic, pharmacist or therapist — so carers, care angels, chemists and
 * technicians, who are read-only in v1, would have been unable to <em>ask for time off</em>. Booking
 * leave is not a clinical mutation. The real authorization is per-record and lives in
 * {@link AbsenceService}: you write your own, an administrator writes anyone's.
 */
@RestController
@RequestMapping("/api/absences")
public class AbsenceResource {

    private final AbsenceService absenceService;

    public AbsenceResource(AbsenceService absenceService) {
        this.absenceService = absenceService;
    }

    /**
     * The caller's own absences, requested and approved alike.
     *
     * <p>{@code from} and {@code to} are optional and inclusive, mirroring the roster's range read;
     * omitting them returns everything, which is what a "my time off" list wants and a calendar does
     * not. An absence <em>overlapping</em> the range is returned, not merely one starting inside it —
     * a holiday that began last month and runs into the week being drawn is exactly the case a
     * calendar must not miss.
     *
     * <p>{@code professionalId} lets a roster administrator read somebody else's, which is what the
     * approval queue needs; anyone else asking for an id that is not their own gets a 403 rather than
     * an empty list. Defaults to the caller, so the ordinary call names nobody.
     */
    @GetMapping
    public List<Absence> own(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String professionalId
    ) {
        if (from == null && to == null && professionalId == null) {
            return absenceService.own();
        }
        String target = professionalId == null ? absenceService.callerProfileId().orElse(null) : professionalId;
        if (target == null) {
            // No profile means no absences, the same ordinary state the roster's own read treats as
            // an empty list rather than an error.
            return List.of();
        }
        return absenceService.inRange(target, from, to);
    }

    /** Every absence on the estate — the roster administrator's view. */
    @GetMapping("/all")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public List<Absence> all() {
        return absenceService.all();
    }

    @GetMapping("/{id}")
    public Absence one(@PathVariable String id) {
        return absenceService.visible(id);
    }

    /**
     * Request time off. An administrator posting may name another professional, backdate, and set
     * {@code APPROVED} directly — that is the retrospective-sickness path.
     *
     * <p><b>Deliberately not {@code @Valid}.</b> {@link Absence#getProfessionalId()} is
     * {@code @NotNull} because a stored absence must belong to someone, but a professional requesting
     * their own leave does not send it — the server decides whose it is, exactly as it decides the
     * status. Bean validation on the body would reject that request with a 400 before
     * {@link AbsenceService#request} ever got to fill the field in, which is a confusing failure for
     * the single most common call this resource takes. The service validates the whole thing instead,
     * where it can tell an administrator's submission from a clinician's.
     */
    @PostMapping
    public ResponseEntity<Absence> request(@RequestBody Absence absence) throws URISyntaxException {
        Absence saved = absenceService.request(absence);
        return ResponseEntity.created(new URI("/api/absences/" + saved.getId())).body(saved);
    }

    /** Grant a request. Admin only, and 409 while the days are still rostered. */
    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public Absence approve(@PathVariable String id) {
        return absenceService.approve(id);
    }

    /** An administrator declining, or a professional withdrawing their own pending request. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        absenceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AbsenceService.InvalidAbsenceException.class)
    public ResponseEntity<String> handleInvalid(AbsenceService.InvalidAbsenceException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @ExceptionHandler(AbsenceService.AbsenceForbiddenException.class)
    public ResponseEntity<String> handleForbidden(AbsenceService.AbsenceForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.getMessage());
    }

    /**
     * <b>409, and it names the rounds.</b>
     *
     * <p>The whole point of blocking approval is that cover gets arranged rather than discovered, so
     * the response has to say what is in the way. The administrator reassigns those rounds — see
     * {@code PUT /api/duty-roster/{id}/reassign} — and retries this request unchanged.
     */
    @ExceptionHandler(AbsenceService.AbsenceConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(AbsenceService.AbsenceConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            Map.of("message", exception.getMessage(), "conflictingRosterIds", exception.getConflicts())
        );
    }
}
