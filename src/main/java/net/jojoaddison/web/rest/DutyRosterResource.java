package net.jojoaddison.web.rest;

import jakarta.validation.Valid;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.CustomerDayPlanService;
import net.jojoaddison.service.DutyRosterService;
import net.jojoaddison.service.RosterTrailService;
import net.jojoaddison.service.dto.DutyRosterDtos;
import net.jojoaddison.service.dto.PatientDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.PaginationUtil;

/**
 * Professional duty-roster assignments (professional-onboarding-workflow.md
 * § Duty roster, assignment-only decision): roster administrators (ROLE_ADMIN)
 * create and remove assignments; professionals read their own. There is
 * deliberately no self-subscription endpoint.
 */
@RestController
/*
 * Singular, and the bare GET is your own roster (docs/duty-roster.md § 1, DR1). A clinician asking
 * for "the duty roster" means theirs; the administrator's whole-estate view is the special case and
 * earns the longer path, /all.
 *
 * THIS INVERTED WHICH OPERATION IS THE DEFAULT, so the security annotations were re-derived rather
 * than moved. Previously the bare GET was the admin list and /my was the clinician's; now the bare
 * GET is .authenticated() and /all carries the @PreAuthorize(ADMIN) that used to sit on it. Copying
 * the old annotations across would either lock every clinician out of their own roster or publish
 * the whole estate's to anyone signed in.
 *
 * Earlier history: moved from /api/onboarding/duty-rosters on 2026-08-11, because the roster is
 * owned by this service and was only under that prefix because WP6 built it beside the applicant
 * pipeline. Under /api/** the matchers additionally require CLINICAL_MUTATION for POST and DELETE,
 * which every admin already holds.
 */
@RequestMapping("/api/duty-roster")
public class DutyRosterResource {

    private static final Logger log = LoggerFactory.getLogger(DutyRosterResource.class);

    private final DutyRosterRepository dutyRosterRepository;
    private final ProfileRepository profileRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final DutyRosterService dutyRosterService;
    private final RosterTrailService rosterTrailService;
    private final CustomerDayPlanService customerDayPlanService;

    public DutyRosterResource(
        DutyRosterRepository dutyRosterRepository,
        ProfileRepository profileRepository,
        DomainEventPublisher domainEventPublisher,
        DutyRosterService dutyRosterService,
        RosterTrailService rosterTrailService,
        CustomerDayPlanService customerDayPlanService
    ) {
        this.dutyRosterRepository = dutyRosterRepository;
        this.profileRepository = profileRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.dutyRosterService = dutyRosterService;
        this.rosterTrailService = rosterTrailService;
        this.customerDayPlanService = customerDayPlanService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<DutyRoster> assign(@Valid @RequestBody DutyRoster dutyRoster) throws URISyntaxException {
        // Identifiers only. This used to log the whole object, which was harmless while a roster row
        // was six scalars and is not now that it carries customer names, addresses and phone numbers
        // (DR2). DutyRoster.toString() omits the visits for the same reason; both have to hold.
        log.debug(
            "REST request to assign duty roster for professional {} on {} ({})",
            dutyRoster.getProfessionalId(),
            dutyRoster.getDate(),
            dutyRoster.getShift()
        );
        if (dutyRoster.getId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A new assignment cannot already have an id");
        }
        if (profileRepository.findById(dutyRoster.getProfessionalId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown professional profile");
        }
        DutyRoster saved = dutyRosterService.assign(dutyRoster);
        domainEventPublisher.publishEntityCreated("DutyRoster", saved.getId(), null, SecurityUtils.getCurrentUserLogin().orElse("system"));
        return ResponseEntity.created(new URI("/api/duty-roster/" + saved.getId())).body(saved);
    }

    /**
     * A round the caller could not have — visit times outside the shift window, an end before its
     * start, or a double-booking — is a 400 naming what clashed.
     *
     * <p>Not a 409. The conflict is with the request's own content or with data the administrator can
     * see and change; nothing on the server is in a state that would let a retry of the same body
     * succeed. (DR4's absence approval genuinely is a 409, because there the conflict is with a
     * separate resource the caller may resolve and retry against.)
     */
    @ExceptionHandler(DutyRosterService.InvalidRoundException.class)
    public ResponseEntity<String> handleInvalidRound(DutyRosterService.InvalidRoundException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<Void> unassign(@PathVariable String id) {
        dutyRosterRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Every assignment on the estate, a page at a time. Admin only — see the inversion note on the
     * class.
     *
     * <p><b>Paginated, and projected.</b> It used to be an unbounded {@code findAll} returning stored
     * {@link DutyRoster} documents, which made it both the largest response this service could
     * produce and a disclosure of every customer snapshot on the estate. {@code X-Total-Count} is now
     * the collection's real count rather than the size of the list in the body — the distinction
     * {@code GET /api/patients} still gets wrong. The pattern is {@code ProfileResource}'s, which is
     * the only genuinely paginated read this service had.
     *
     * @param pageable page, size and sort; defaults to date then shift — see
     *     {@link DutyRosterService#estateRoster}.
     */
    @GetMapping("/all")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<List<DutyRosterDtos.Round>> listAll(@org.springdoc.core.annotations.ParameterObject Pageable pageable) {
        Page<DutyRoster> page = dutyRosterService.estateRoster(pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(DutyRosterDtos.rounds(page.getContent()));
    }

    /**
     * The caller's own assignments — the default meaning of "the duty roster", and read-only per the
     * assignment-only policy. An account with no profile gets an empty list rather than an error:
     * having no roster is an ordinary state, not a failure.
     *
     * <p>{@code from} and {@code to} are optional, inclusive, and independent (DR2). Omitting both
     * returns the whole roster, which is what DR1 did and what the dashboard still asks for, so the
     * range is an addition rather than a change.
     *
     * <p><b>Projected, and this is the read that made the projection necessary.</b> Omitting both
     * bounds is the dashboard's every-load call, so serialising the stored document here shipped
     * every customer name, address and phone number on the caller's whole roster to draw a grid of
     * shift names. {@link DutyRosterDtos.Round} has nowhere to put them; {@link #day} is where the
     * snapshot is served, deliberately and one day at a time.
     */
    @GetMapping
    public List<DutyRosterDtos.Round> myAssignments(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ownProfileId()
            .map(professionalId -> DutyRosterDtos.rounds(dutyRosterService.forProfessional(professionalId, from, to)))
            .orElse(List.of());
    }

    /**
     * The caller's rounds for one date, with customer snapshots refreshed (DR6, docs § 6).
     *
     * <p>The day view's read, and <b>the only one that carries customer names, addresses and phone
     * numbers to a browser</b>. That is true because this is the only roster read that returns the
     * stored {@link DutyRoster} — the range read and {@code /all} go through
     * {@link DutyRosterDtos.Round}, which has no field to put a snapshot in, and the summary read
     * returns counts. <b>Until item 7 this paragraph was an intention rather than a description:</b>
     * all three reads serialised the document, so the claim was false on the wire in the exact place a
     * reader would come to check it. If you add a fourth read, it takes the projection unless it has
     * this one's reason not to — opened deliberately, by someone about to walk to the address.
     *
     * <p>Refreshing here is a write on a read path, which § 6 flagged and accepted — it is what makes
     * the snapshot self-healing whenever `hc-patient` is up. See
     * {@link DutyRosterService#refreshSnapshots}.
     *
     * <p>No profile means an empty list rather than an error, exactly as the range read treats it:
     * having no roster is an ordinary state.
     */
    @GetMapping("/day/{date}")
    public List<DutyRoster> day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ownProfileId().map(professionalId -> dutyRosterService.dayFor(professionalId, date)).orElse(List.of());
    }

    /**
     * The caller's year, one record per day they are rostered — the year view's read (DR2).
     *
     * <p>Days with nothing on them are absent rather than returned empty; see
     * {@link DutyRosterService#summariseYear}. Defaults to the current year so the common call needs
     * no parameter.
     */
    @GetMapping("/summary")
    public List<DutyRosterService.DaySummary> summary(@RequestParam(required = false) Integer year) {
        int target = year == null ? LocalDate.now().getYear() : year;
        return ownProfileId().map(professionalId -> dutyRosterService.summariseYear(professionalId, target)).orElse(List.of());
    }

    /**
     * Move a whole round to another professional (DR4, docs § 8) — the default form of cover.
     *
     * <p>The round's customers, their times and their order move together, in one action, because
     * they are a coherent plan and splitting them by hand loses it. Use this before approving an
     * absence that {@code PUT /api/absences/{id}/approve} has just refused with a 409.
     */
    @PutMapping("/{id}/reassign")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public DutyRoster reassign(@PathVariable String id, @RequestParam String professionalId) {
        requireProfile(professionalId);
        return dutyRosterService.reassignRound(id, professionalId);
    }

    /**
     * Move a single visit to another professional (DR4, docs § 8) — the fallback.
     *
     * <p>For when one person cannot take the whole round. The visit lands in the target's round for
     * the same date and shift, creating one if they have none. Returns the <em>target</em> round,
     * since that is where the visit now is.
     */
    @PutMapping("/{id}/visits/{visitId}/reassign")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public DutyRoster reassignVisit(@PathVariable String id, @PathVariable String visitId, @RequestParam String professionalId) {
        requireProfile(professionalId);
        return dutyRosterService.reassignVisit(id, visitId, professionalId);
    }

    /**
     * The target of a reassignment must be somebody who exists — checked here, as {@link #assign}
     * checks it, so both write paths refuse an unknown id the same way.
     *
     * <p>Without this a mistyped id is <b>silently destructive</b> rather than an error: the round is
     * saved against a professional nobody is, so it leaves the source clinician's roster, never
     * appears on anyone else's, and survives only in the administrator's {@code /all} list. The
     * customers on it are simply not visited, and nothing anywhere says so.
     */
    private void requireProfile(String professionalId) {
        if (professionalId == null || professionalId.isBlank() || profileRepository.findById(professionalId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown professional profile");
        }
    }

    /**
     * Clear customer snapshots older than the retention window, on demand (DR2, docs § 6).
     *
     * <p>Admin-only and idempotent, exposed for the same reasons {@code ComplianceResource} exposes
     * its sweep: an operator should be able to run a privacy sweep without waiting for 04:00, and a
     * scheduled job nobody can trigger is a job nobody can verify.
     */
    @PostMapping("/purge-snapshots")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public DutyRosterService.PurgeResult purgeSnapshots() {
        return dutyRosterService.purgeExpiredSnapshots();
    }

    /**
     * A customer's recent activity, for a clinician who has them on their own round (DR3).
     *
     * <p>Lives under the duty-roster resource because the <b>permission derives from the roster</b>,
     * not from the patient: the caller may read this only while that customer is on one of their
     * rounds within ±30 days. {@link RosterTrailService} owns that check and it runs on every read.
     *
     * <p>Authenticated rather than role-gated, and that is not an oversight. Every clinical authority
     * does rounds, including the four that are read-only under {@code CLINICAL_MUTATION}, and the
     * roster is a far tighter boundary than any role would be — a doctor with no rounds sees nothing
     * here, which is the correct answer.
     */
    @GetMapping("/customers/{customerId}/trail")
    public List<PatientDtos.ActivityLogEntry> customerTrail(@PathVariable String customerId) {
        return rosterTrailService.trailFor(customerId, LocalDate.now());
    }

    /**
     * Not on your roster is a <b>403</b>, and never an empty list.
     *
     * <p>"Nothing happened this week" and "you may not look" are different answers and the screen
     * treats them differently; collapsing the second into the first would hide an authorization
     * failure behind a plausible blank panel. The body says which customer was refused only in the
     * sense of repeating what the caller already sent — it reveals nothing about customers they
     * cannot see, so the endpoint cannot be used to probe for ids.
     */
    @ExceptionHandler(RosterTrailService.TrailForbiddenException.class)
    public ResponseEntity<String> handleTrailForbidden(RosterTrailService.TrailForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.getMessage());
    }

    /**
     * A customer's own day plan — who is visiting them, and when (2026-09-04).
     *
     * <p><b>Reached from hc-patient's portal, through that gateway's {@code professionalservice}
     * route.</b> This service holds the only copy of the answer: hc-patient's {@code DutyRoster} had
     * no date and no patient id and was deleted, and hc-admin's took its subject from the path and
     * checked nothing about who was asking, so it was deleted with the {@code ROLE_PATIENT} matcher
     * that made it reachable. The data is {@code visits[].customerId}, here.
     *
     * <p><b>The id in the path is the same shape hc-admin's endpoint had, and the difference is what
     * this one does with it.</b> {@link CustomerDayPlanService} refuses unless the caller <em>is</em>
     * that customer, resolved from the token rather than from the request — so the path variable
     * names the subject and cannot choose it.
     *
     * <p>Authenticated rather than role-gated, like the trail below and for a sharper version of the
     * same reason: the authority that would be named here is {@code ROLE_PATIENT}, which this
     * gateway never issues and which no rule in this service should start depending on. Identity is
     * the boundary, and the service enforces it.
     *
     * <p>{@code from} and {@code to} are optional and inclusive; omitting both gives the fortnight
     * around today rather than the patient's whole history.
     */
    @GetMapping("/customer/{customerId}")
    public List<DutyRosterDtos.CustomerVisit> customerDayPlan(
        @PathVariable String customerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return customerDayPlanService.forCustomer(customerId, from, to, LocalDate.now());
    }

    /**
     * Not your day plan is a <b>403</b>, and so is a customer id that belongs to nobody.
     *
     * <p>Identical status, identical body, and that is the whole point rather than an accident of
     * implementation: two different answers here would make this endpoint an oracle for "does this
     * customer exist", answerable for every id on the platform by any authenticated caller on three
     * stacks. {@code CustomerDayPlanForbiddenIT} asserts the two responses are byte-for-byte the
     * same.
     *
     * <p>The body repeats nothing the caller did not already send.
     */
    @ExceptionHandler(CustomerDayPlanService.DayPlanForbiddenException.class)
    public ResponseEntity<String> handleDayPlanForbidden(CustomerDayPlanService.DayPlanForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.getMessage());
    }

    /** The caller's profile id, or empty when the account has no profile — an ordinary state. */
    private Optional<String> ownProfileId() {
        String login = SecurityUtils.getCurrentUserLogin()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
        return profileRepository.findByAccountId(login).map(profile -> profile.getId());
    }
}
