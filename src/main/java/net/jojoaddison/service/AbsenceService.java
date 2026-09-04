package net.jojoaddison.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.Absence;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.enumeration.AbsenceStatus;
import net.jojoaddison.domain.enumeration.ShiftType;
import net.jojoaddison.repository.AbsenceRepository;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Requesting and granting time off (docs/duty-roster.md § 8, DR4).
 *
 * <p>Day status had no representation before this: {@code OFF} was merely the absence of a roster
 * row, and holiday and sick did not exist at all. The shape is deliberately small — the clinician
 * requests, a roster administrator approves — and three rules carry most of the weight.
 *
 * <p><b>1. Professionals cannot backdate; administrators can.</b> An absence a professional requests
 * may not start before today, for any type. That is not an anti-fraud measure so much as a
 * calendar-integrity one: a day already worked cannot become a day off. But <em>sickness is normally
 * reported after it begins</em>, so the retrospective path has to exist or the most common real case
 * has nowhere to go — and it exists as an administrator recording it, which is also how it happens in
 * practice, over the phone at 06:00.
 *
 * <p><b>2. Approval is blocked while the days are still assigned.</b> {@link #approve} answers 409
 * naming the rounds that clash, so cover is arranged <em>before</em> the absence is granted rather
 * than discovered afterwards by whoever opens the roster next. This is the one place a 409 is right
 * where the rest of this subsystem uses 400: the conflict is with a separate resource the caller can
 * go and resolve — reassign the round, then retry the same request unchanged.
 *
 * <p><b>3. A request is visible to its requester and to roster administrators, and to nobody else.</b>
 * Enforced here rather than at the resource, because the same rule has to hold for every caller, and
 * because "who may see this absence" is a question about the absence.
 *
 * <p>There is no {@code REJECTED} state. Declining is deletion, and so is a professional withdrawing
 * their own request — see {@code AbsenceStatus}.
 */
@Service
public class AbsenceService {

    private static final Logger log = LoggerFactory.getLogger(AbsenceService.class);

    private final AbsenceRepository absenceRepository;
    private final DutyRosterRepository dutyRosterRepository;
    private final ProfileRepository profileRepository;

    public AbsenceService(
        AbsenceRepository absenceRepository,
        DutyRosterRepository dutyRosterRepository,
        ProfileRepository profileRepository
    ) {
        this.absenceRepository = absenceRepository;
        this.dutyRosterRepository = dutyRosterRepository;
        this.profileRepository = profileRepository;
    }

    /** A request the caller may not make — bad dates, backdating, or somebody else's absence. 400. */
    public static class InvalidAbsenceException extends RuntimeException {

        public InvalidAbsenceException(String message) {
            super(message);
        }
    }

    /** The caller may not see or touch this absence. 403 — see the visibility rule on the class. */
    public static class AbsenceForbiddenException extends RuntimeException {

        public AbsenceForbiddenException(String message) {
            super(message);
        }
    }

    /**
     * The days are still rostered. 409, naming what clashes so it can be reassigned and retried.
     */
    public static class AbsenceConflictException extends RuntimeException {

        private final List<String> conflicts;

        public AbsenceConflictException(String message, List<String> conflicts) {
            super(message);
            this.conflicts = conflicts;
        }

        public List<String> getConflicts() {
            return conflicts;
        }
    }

    // ---------------------------------------------------------------- reads

    /** The caller's own absences, both requested and approved, earliest first. */
    public List<Absence> own() {
        return callerProfileId().map(absenceRepository::findByProfessionalIdOrderByFromDateAsc).orElseGet(List::of);
    }

    /** Every absence on the estate. The resource gates this on ROLE_ADMIN. */
    public List<Absence> all() {
        return absenceRepository.findAllByOrderByFromDateAsc();
    }

    /**
     * Absences overlapping a range, for a professional — what a calendar colours days from.
     *
     * <p>Returns the caller's own unless they are an administrator, so a clinician cannot read a
     * colleague's sick leave by asking for their id.
     *
     * <p><b>Overlapping, not starting within</b> — a holiday that began last month and runs into the
     * week being drawn is exactly the case a calendar must not miss, and the tempting "{@code
     * fromDate} inside the range" form drops it silently.
     *
     * <p>Either bound may be null for an open-ended range, and <b>a missing bound picks a different
     * finder rather than a sentinel date</b>, for the reason spelled out on
     * {@link AbsenceRepository}: {@code LocalDate.MIN}/{@code MAX} are outside what BSON can compare
     * and would return an empty calendar instead of an open-ended one.
     */
    public List<Absence> inRange(String professionalId, LocalDate from, LocalDate to) {
        if (professionalId == null) {
            return List.of();
        }
        if (!isAdmin() && !professionalId.equals(callerProfileId().orElse(null))) {
            throw new AbsenceForbiddenException("You may only read your own absences");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidAbsenceException("'from' must not be after 'to'");
        }
        if (from == null && to == null) {
            return absenceRepository.findByProfessionalIdOrderByFromDateAsc(professionalId);
        }
        if (to == null) {
            return absenceRepository.findByProfessionalIdAndToDateGreaterThanEqualOrderByFromDateAsc(professionalId, from);
        }
        if (from == null) {
            return absenceRepository.findByProfessionalIdAndFromDateLessThanEqualOrderByFromDateAsc(professionalId, to);
        }
        return absenceRepository.findOverlapping(professionalId, from, to);
    }

    // --------------------------------------------------------------- writes

    /**
     * Create a request, or — for an administrator — a record on someone else's behalf.
     *
     * <p>The submitted status and professional are both ignored for a non-administrator: the absence
     * is forced onto the caller's own profile and to {@code REQUESTED}, the same way
     * {@code OnboardingService.upsertOwnProfile} force-sets {@code accountId}. A client cannot decide
     * whose absence it is or that it is already approved.
     */
    public Absence request(Absence submitted) {
        if (submitted.getFromDate() == null || submitted.getToDate() == null) {
            throw new InvalidAbsenceException("An absence needs a from and a to date");
        }
        if (submitted.getToDate().isBefore(submitted.getFromDate())) {
            throw new InvalidAbsenceException("'toDate' must not be before 'fromDate'");
        }
        // Checked here rather than by @Valid on the body — see the note on AbsenceResource.request
        // for why that annotation is absent. Without this the null reaches Mongo and fails as a
        // write error rather than as a request the caller can correct.
        if (submitted.getType() == null) {
            throw new InvalidAbsenceException("An absence needs a type: HOLIDAY, SICK or OTHER");
        }
        boolean admin = isAdmin();
        Absence absence = new Absence().fromDate(submitted.getFromDate()).toDate(submitted.getToDate()).type(submitted.getType());

        if (admin) {
            // The retrospective-sickness path, and the only way to record an absence for someone
            // else. An administrator may backdate and may grant in the same action.
            String target = submitted.getProfessionalId();
            if (target == null || profileRepository.findById(target).isEmpty()) {
                throw new InvalidAbsenceException("Unknown professional profile");
            }
            absence.professionalId(target).status(submitted.getStatus() == null ? AbsenceStatus.REQUESTED : submitted.getStatus());
        } else {
            String own = callerProfileId().orElseThrow(() -> new AbsenceForbiddenException("This account has no professional profile"));
            if (submitted.getFromDate().isBefore(LocalDate.now())) {
                throw new InvalidAbsenceException(
                    "An absence cannot start in the past. Ask a roster administrator to record sickness that has already begun."
                );
            }
            absence.professionalId(own).status(AbsenceStatus.REQUESTED);
        }
        Absence saved = absenceRepository.save(absence);
        log.debug("Absence {} recorded for professional {} ({})", saved.getId(), saved.getProfessionalId(), saved.getStatus());
        return saved;
    }

    /**
     * Grant a requested absence, unless the days are still assigned.
     *
     * <p>Idempotent on an already-approved absence — approving twice is not an error, it is two
     * administrators reaching the same conclusion.
     */
    public Absence approve(String id) {
        Absence absence = absenceRepository.findById(id).orElseThrow(() -> new InvalidAbsenceException("No such absence"));
        if (absence.getStatus() == AbsenceStatus.APPROVED) {
            return absence;
        }
        List<String> conflicts = assignedRoundsDuring(absence);
        if (!conflicts.isEmpty()) {
            throw new AbsenceConflictException(
                "Cannot approve: the professional is still rostered on %d day(s) in this range".formatted(conflicts.size()),
                conflicts
            );
        }
        return absenceRepository.save(absence.status(AbsenceStatus.APPROVED));
    }

    /**
     * Delete an absence: an administrator declining one, or a professional withdrawing their own.
     *
     * <p>A professional may only withdraw while it is still {@code REQUESTED}. Once granted, cover
     * may already have been arranged around it, so coming back is a conversation with the roster
     * administrator rather than a button.
     */
    public void delete(String id) {
        Absence absence = absenceRepository.findById(id).orElseThrow(() -> new InvalidAbsenceException("No such absence"));
        if (isAdmin()) {
            absenceRepository.delete(absence);
            return;
        }
        String own = callerProfileId().orElse(null);
        if (!absence.getProfessionalId().equals(own)) {
            // Same 403 as reading someone else's, and for the same reason: the caller learns nothing
            // about absences that are not theirs, including whether the id exists.
            throw new AbsenceForbiddenException("You may only withdraw your own absence");
        }
        if (absence.getStatus() != AbsenceStatus.REQUESTED) {
            throw new InvalidAbsenceException("An approved absence is withdrawn by a roster administrator");
        }
        absenceRepository.delete(absence);
    }

    /** Enforces the visibility rule for a single record. */
    public Absence visible(String id) {
        Absence absence = absenceRepository.findById(id).orElseThrow(() -> new AbsenceForbiddenException("No such absence"));
        if (!isAdmin() && !absence.getProfessionalId().equals(callerProfileId().orElse(null))) {
            throw new AbsenceForbiddenException("You may only read your own absences");
        }
        return absence;
    }

    // -------------------------------------------------------------- helpers

    /**
     * Round ids the professional still holds inside the absence's dates.
     *
     * <p>Note this counts <em>rounds</em>, not visits: a shift with no visits is still a shift
     * somebody has to cover, and approving leave over it would leave a hole nobody notices.
     *
     * <p><b>An {@code OFF} round is the exception that sentence does not cover, and it is excluded.</b>
     * A rostered rest day is not work anybody has to cover, so granting leave across it leaves no
     * hole. Counting it would report a conflict on a day the clinician was already not working and
     * refuse the approval with a 409 naming a rest day — which reads as a bug in the roster rather
     * than a rule. The filter went in with {@code OFF} on 2026-09-04; until then every value of the
     * enum meant "worked", which is what made "rounds, not visits" a complete rule rather than an
     * almost-complete one.
     *
     * <p><b>The day before counts too, if it is a {@code NIGHT}.</b> That shift runs 23:00 until
     * 07:00 the next morning, so a `NIGHT` round dated the day before an absence starts is worked
     * almost entirely <em>inside</em> it. Asking only for rounds dated within the range reads
     * correctly and admits exactly that case — the clinician is granted leave from the 4th and is
     * still on the ward until 07:00 on the 4th, with nothing having flagged it. This is the same
     * wrap {@code DutyRosterRepository.findRoundsAround} exists for, and it has to be handled
     * everywhere a date range meets a shift, not just in the overlap validator.
     */
    public List<String> assignedRoundsDuring(Absence absence) {
        LocalDate eve = absence.getFromDate().minusDays(1);
        return dutyRosterRepository
            .findRoundsInRange(absence.getProfessionalId(), eve, absence.getToDate())
            .stream()
            // A rest day is not cover, so it is not a conflict.
            .filter(round -> round.getShift() != ShiftType.OFF)
            // Everything from the eve is out except a NIGHT, which reaches into the first absent day.
            .filter(round -> !round.getDate().equals(eve) || round.getShift() == ShiftType.NIGHT)
            .map(DutyRoster::getId)
            .toList();
    }

    public Optional<String> callerProfileId() {
        return SecurityUtils.getCurrentUserLogin().flatMap(profileRepository::findByAccountId).map(net.jojoaddison.domain.Profile::getId);
    }

    private boolean isAdmin() {
        return SecurityUtils.hasCurrentUserThisAuthority(net.jojoaddison.security.AuthoritiesConstants.ADMIN);
    }
}
