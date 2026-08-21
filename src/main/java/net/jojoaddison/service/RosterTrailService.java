package net.jojoaddison.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.dto.PatientDtos.ActivityLogEntry;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The recent activity trail for a customer on the caller's own round (docs/duty-roster.md § 7, DR3).
 *
 * <p>The day-view popup shows, beside each visit, what has happened to that customer lately. That is
 * patient data, so the question this class exists to answer is not "what happened" but <b>"may this
 * clinician see it"</b>.
 *
 * <p><b>The boundary is the caller's own roster, ±30 days, checked on every read.</b> It comes from
 * {@link DutyRosterService#trailCustomerIds}, a local indexed query over this service's own duty
 * roster. Three properties follow, and they are the reason it is done this way:
 *
 * <ul>
 *   <li><b>It is never stale.</b> A customer taken off the roster loses the trail on the next
 *       request, not whenever a credential happens to turn over.
 *   <li><b>It fails closed for free.</b> {@code professionalservice} accepts tokens minted by the
 *       {@code hc-admin} and {@code hc-patient} gateways too — they share one signing key. Such a
 *       caller has no {@code Profile} here, so the roster set is empty and every trail read is a 403,
 *       with no coordination between the three stacks and nothing to remember.
 *   <li><b>It costs nothing to carry.</b> No identifiers in the token, which is a bearer credential
 *       that lives in browser storage and is easy to log wholesale.
 * </ul>
 *
 * <p>The plan originally specified a {@code customerIds} JWT claim computed at sign-in, on the
 * reasoning that the check would otherwise be "a runtime call between stacks". That premise does not
 * hold: {@code professionalservice} owns the roster and serves the trail, so the check is local. The
 * claim would have added token growth, staleness bounded by the token's lifetime — up to 24 hours for
 * a browser, whose tokens do not refresh — a sign-in dependency from the gateway on this service, and
 * a token-structure change on a key three production stacks share. Owner decision, 2026-08-21; see
 * {@code duty-roster.md} § 7.
 *
 * <p><b>Only {@code ActivityLogEntry}.</b> {@code Visitation}, {@code MedicationRecord} and
 * {@code ClinicalReport} are deliberately not merged in: four cross-stack reads per customer would
 * make the day view slow for a richer picture nobody asked for.
 */
@Service
public class RosterTrailService {

    /** How far back the trail reaches. Rolling, so it needs no pruning. */
    public static final int TRAIL_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(RosterTrailService.class);

    private final DutyRosterService dutyRosterService;
    private final ProfileRepository profileRepository;
    private final PatientServiceClient patientServiceClient;

    public RosterTrailService(
        DutyRosterService dutyRosterService,
        ProfileRepository profileRepository,
        PatientServiceClient patientServiceClient
    ) {
        this.dutyRosterService = dutyRosterService;
        this.profileRepository = profileRepository;
        this.patientServiceClient = patientServiceClient;
    }

    /** Raised when the customer is not on the caller's roster; the resource turns it into a 403. */
    public static class TrailForbiddenException extends RuntimeException {

        public TrailForbiddenException(String message) {
            super(message);
        }
    }

    /**
     * The customer's last {@link #TRAIL_DAYS} days of activity, newest first.
     *
     * <p>Throws rather than returning empty when the caller is not entitled, because the two mean
     * very different things to the screen: "nothing happened this week" is a rendered empty state,
     * and "you may not look" is not something to render at all. Returning empty for both would hide
     * an authorization failure behind a plausible blank panel — the same class of mistake as a
     * `listCases` scope that selects everything when it does not know who is asking.
     *
     * <p>An unreachable patient stack <em>does</em> yield an empty list, silently.
     * {@link PatientServiceClient} degrades by contract, and the day view treats a blank trail the way
     * the dashboard treats an unreachable {@code adminservice}: the visit, its times and its address
     * are what the clinician actually needs at the door, and they come from this service.
     */
    public List<ActivityLogEntry> trailFor(String customerId, LocalDate today) {
        if (customerId == null || customerId.isBlank()) {
            throw new TrailForbiddenException("No customer named");
        }
        Set<String> permitted = ownRosterCustomers(today);
        if (!permitted.contains(customerId)) {
            // Deliberately does not distinguish "not on your roster" from "no such customer". The
            // caller learns nothing about customers they cannot see, so the endpoint cannot be used
            // to probe for ids.
            log.debug("Trail denied: customer not on the caller's roster within the window");
            throw new TrailForbiddenException("Customer is not on your duty roster");
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(TRAIL_DAYS));
        return patientServiceClient
            .activityLogs()
            .stream()
            .filter(entry -> customerId.equals(entry.patientId()))
            .filter(entry -> entry.createdDate() != null && entry.createdDate().isAfter(cutoff))
            .sorted(Comparator.comparing(ActivityLog::createdDate).reversed())
            .map(RosterTrailService::toEntry)
            .toList();
    }

    /** The caller's permitted customer set, or empty when they have no profile here. */
    public Set<String> ownRosterCustomers(LocalDate today) {
        Optional<String> professionalId = SecurityUtils.getCurrentUserLogin()
            .flatMap(profileRepository::findByAccountId)
            .map(net.jojoaddison.domain.Profile::getId);
        return professionalId.map(id -> dutyRosterService.trailCustomerIds(id, today)).orElseGet(Set::of);
    }

    /**
     * patientservice's {@code ActivityLog} has a {@code name} and a {@code createdDate}; the shape the
     * frontend already renders wants a label, a title and an occurredAt. Mapped the same way
     * {@code PatientDirectoryService} maps it, so the two surfaces agree.
     */
    private static ActivityLogEntry toEntry(ActivityLog entry) {
        String at = entry.createdDate() == null ? null : entry.createdDate().toString();
        return new ActivityLogEntry(entry.id(), at, entry.name(), entry.name(), entry.description(), at);
    }
}
