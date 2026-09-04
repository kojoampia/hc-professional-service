package net.jojoaddison.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Visit;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.dto.DutyRosterDtos.CustomerVisit;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Who is visiting me, and when" — the patient day plan (2026-09-04).
 *
 * <p>This service holds the only copy of the answer. hc-patient's own {@code DutyRoster} had no date
 * and no patient id, so its portal was never fed from it and the entity was deleted on 2026-09-01;
 * hc-admin's had a patient id and no ownership check, and was deleted on 2026-09-04 along with the
 * {@code ROLE_PATIENT} carve-out that made it reachable. What is left is {@code visits[].customerId}
 * here, which is where the data actually is.
 *
 * <h2>403, never an empty list</h2>
 *
 * <p>The idiom {@link RosterTrailService} established, for the same reason: "nothing is planned this
 * fortnight" and "you may not look" are different answers, and the screen renders the first. Folding
 * the second into it hides an authorization failure behind a plausible blank panel.
 *
 * <h2>An unknown customer is refused identically to an unauthorised one</h2>
 *
 * <p>Same status, same message, same amount of work. Diverging them — a 404 for an id nobody holds,
 * a 403 for one somebody else does — turns this endpoint into an oracle answering "does this
 * customer exist" for every id anyone cares to try, across the whole platform, to any authenticated
 * caller on three stacks. The check below therefore never asks whether the requested customer
 * exists: it asks whether the requested customer <em>is the caller</em>, which is a question about
 * the caller alone.
 *
 * <h2>Who the caller is</h2>
 *
 * <p>The token's {@code email} claim, resolved to a {@code patientservice} profile and from there to
 * its {@code patientId} — the same chain hc-patient's own {@code PatientScope} follows, and the only
 * one available: this service runs {@code skipUserManagement} and the token's subject is a login
 * that matches nothing here. The three gateways share a signing key, so a patient token validates;
 * this stack's own tokens carry no {@code email} claim at all, so a clinician asking for a day plan
 * resolves to nobody and is refused, which is the correct answer and needs no coordination.
 *
 * <p><b>There is deliberately no administrator carve-out.</b> An administrator can already read the
 * whole estate through {@code GET /api/duty-roster/all}, so a carve-out here would buy nothing — and
 * it would make the endpoint answer differently for different callers, which is exactly the shape
 * that brings the existence oracle back: an unknown id would be an empty list for one caller and a
 * 403 for another.
 *
 * <h2>It fails closed</h2>
 *
 * <p>An unreachable patient stack means the caller cannot be resolved, and an unresolved caller is
 * refused. That is the one direction this may fail in: a day plan is patient data, and serving it to
 * somebody who might not be the patient because a sibling service was down is not a degradation
 * anyone would accept.
 */
@Service
public class CustomerDayPlanService {

    /**
     * How far the default window reaches, in each direction.
     *
     * <p>A day plan is about the days around now. Bounded by construction rather than by the
     * caller's manners: an unbounded read here would grow with a patient's whole history, and this
     * repository has already had to delete one whole-estate finder for exactly that reason. A caller
     * wanting further out passes {@code from} and {@code to}.
     */
    static final int DEFAULT_DAYS_BACK = 7;
    static final int DEFAULT_DAYS_AHEAD = 14;

    private static final Logger log = LoggerFactory.getLogger(CustomerDayPlanService.class);

    private final DutyRosterRepository dutyRosterRepository;
    private final ProfileRepository profileRepository;
    private final PatientServiceClient patientServiceClient;

    public CustomerDayPlanService(
        DutyRosterRepository dutyRosterRepository,
        ProfileRepository profileRepository,
        PatientServiceClient patientServiceClient
    ) {
        this.dutyRosterRepository = dutyRosterRepository;
        this.profileRepository = profileRepository;
        this.patientServiceClient = patientServiceClient;
    }

    /** Raised when the caller is not the customer they asked about. The resource turns it into 403. */
    public static class DayPlanForbiddenException extends RuntimeException {

        public DayPlanForbiddenException(String message) {
            super(message);
        }
    }

    /**
     * The visits planned for one customer, soonest first.
     *
     * @param customerId the {@code patientservice} {@code Profile.patientId} being asked about
     * @param from inclusive lower bound, or null for {@link #DEFAULT_DAYS_BACK} days ago
     * @param to inclusive upper bound, or null for {@link #DEFAULT_DAYS_AHEAD} days ahead
     * @param today the reference date the defaults are measured from
     * @throws DayPlanForbiddenException if the caller is not that customer — including when the id
     *     belongs to nobody, which is refused in exactly the same way
     */
    public List<CustomerVisit> forCustomer(String customerId, LocalDate from, LocalDate to, LocalDate today) {
        requireCaller(customerId);

        LocalDate start = from == null ? today.minusDays(DEFAULT_DAYS_BACK) : from;
        LocalDate end = to == null ? today.plusDays(DEFAULT_DAYS_AHEAD) : to;
        if (start.isAfter(end)) {
            // The same shape as the range read's own guard. Not a forbidden — the caller is entitled,
            // they have simply asked for a window that cannot contain anything.
            return List.of();
        }

        List<CustomerVisit> plan = new ArrayList<>();
        for (DutyRoster round : dutyRosterRepository.findRoundsForCustomer(customerId, start, end)) {
            String professionalName = nameOf(round.getProfessionalId());
            for (Visit visit : round.getVisits()) {
                // Only this customer's calls. A round serves several people, and returning the round
                // whole would tell one patient who else their clinician is seeing — a disclosure with
                // no purpose behind it. CustomerVisit has nowhere to put another customer's id; this
                // loop is what keeps that true.
                if (customerId.equals(visit.getCustomerId())) {
                    plan.add(
                        new CustomerVisit(
                            round.getId(),
                            round.getDate(),
                            round.getShift(),
                            round.getDuty(),
                            round.getProfessionalId(),
                            professionalName,
                            round.getGeographicSpaceId(),
                            visit.getStartTime(),
                            visit.getEndTime()
                        )
                    );
                }
            }
        }
        return plan;
    }

    /**
     * Refuses unless the caller is the customer they asked about.
     *
     * <p><b>Every refusal takes this one path and carries this one message.</b> A blank id, a token
     * with no email, an email that resolves to nobody, a profile with no {@code patientId} and a
     * customer who is somebody else are five different facts about the world and one answer to the
     * caller, because any difference between them is a bit of information about an id they do not
     * own.
     */
    private void requireCaller(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw forbidden();
        }
        Optional<String> callerPatientId = SecurityUtils.getCurrentUserEmail()
            .flatMap(patientServiceClient::profileByEmail)
            .map(PatientProfile::patientId);
        if (callerPatientId.filter(customerId::equals).isEmpty()) {
            // Logged without either id. The requested one is the caller's own input and tells a
            // reader nothing; the resolved one identifies a patient, and this line goes to a log with
            // a different retention policy from the database.
            log.debug("Day plan denied: the caller is not the customer they asked about, or could not be resolved");
            throw forbidden();
        }
    }

    /** One message for every refusal — see {@link #requireCaller}. */
    private static DayPlanForbiddenException forbidden() {
        return new DayPlanForbiddenException("Not your day plan");
    }

    /**
     * The clinician's name, or null.
     *
     * <p>Null rather than a placeholder: the plan is still worth rendering with a time and a shift
     * when the profile has gone, and a screen can say "a clinician" better than this can.
     */
    private String nameOf(String professionalId) {
        if (professionalId == null) {
            return null;
        }
        return profileRepository
            .findById(professionalId)
            .map(
                profile ->
                    ((profile.getFirstName() == null ? "" : profile.getFirstName()) +
                        " " +
                        (profile.getLastName() == null ? "" : profile.getLastName())).trim()
            )
            .filter(name -> !name.isEmpty())
            .orElse(null);
    }
}
