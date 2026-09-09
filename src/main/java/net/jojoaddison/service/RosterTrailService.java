package net.jojoaddison.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
     * <p><b>An unreachable patient stack is a 503 and not a quiet week</b> (backlog item 24). It used
     * to yield an empty list, because {@link PatientServiceClient} answered empty on every failure and
     * this method could not tell that from a customer who genuinely had no activity — "wrong but
     * harmless-looking", as the item puts it. It is not harmless. Every row of a trail comes from the
     * sibling, so an outage renders the panel a clinician uses to ask <em>what has happened to this
     * person lately</em> as the statement that nothing has. That is a clinical claim this service is in
     * no position to make. Raising instead is cheap here in a way it would not be elsewhere: the trail
     * is its own endpoint behind a popup, so a 503 costs the panel and not the round — the visit, its
     * times and its address still render, and they come from this service.
     *
     * <p>Deliberately <em>not</em> the same answer {@link DutyRosterService} gives to the same signal.
     * That one has stored snapshots to fall back on and keeps them; this one has nothing to show but
     * what it could not read.
     *
     * @throws PatientServiceUnavailableException when the activity collection could not be read
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
        // The window is measured against loggedAt — when the visit happened — because that is what a
        // trail is for. createdDate is only the filing date, and a note typed up a week late would
        // otherwise fall inside a window its visit fell outside of.
        Instant cutoff = Instant.now().minus(Duration.ofDays(TRAIL_DAYS));
        // Uncaught on purpose: PatientServiceUnavailableException becomes a 503 through
        // ExceptionTranslator. See this method's javadoc, and backlog.md item 24.
        //
        // Scoped at the sibling, not here (backlog.md item 23). This read used to fetch every activity
        // log in the estate and keep one customer's — correct since item 22 and, at ~1260 rows and
        // seven requests a read, the most expensive way possible to answer a question about one person.
        // The in-memory customerId filter below is kept as a cheap assertion over one patient's rows;
        // it is no longer what makes the answer right.
        return patientServiceClient
            .activityLogs(customerId)
            .stream()
            .filter(entry -> customerId.equals(entry.patientId()))
            .filter(entry -> occurredAt(entry) != null && occurredAt(entry).isAfter(cutoff))
            .sorted(Comparator.comparing(RosterTrailService::occurredAt).reversed())
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
     * When the entry happened, as an instant.
     *
     * <p>{@code loggedAt} is the clinical time and is already an {@code Instant}; {@code createdDate}
     * is a {@code LocalDate} and is promoted to the start of that day in UTC so the two can be
     * compared at all. That promotion is a widening, not a truth — an entry with only a filing date
     * is treated as having happened at midnight, which is the least-wrong option available.
     */
    private static Instant occurredAt(ActivityLog entry) {
        if (entry.loggedAt() != null) {
            return entry.loggedAt();
        }
        return entry.createdDate() == null ? null : entry.createdDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * patientservice's {@code ActivityLog} carries {@code summary}/{@code detail}; the shape the
     * frontend already renders wants a label, a title and an occurredAt. Mapped the same way
     * {@code PatientDirectoryService} maps it, so the two surfaces agree.
     *
     * <p>It used to read {@code name}/{@code description}, which patientservice does not send — see
     * {@code PatientServiceDtos.ActivityLog}. Every trail entry was a pair of nulls, and before that
     * the collection never arrived at all.
     */
    private static ActivityLogEntry toEntry(ActivityLog entry) {
        Instant at = occurredAt(entry);
        String text = at == null ? null : at.toString();
        String filed = entry.createdDate() == null ? null : entry.createdDate().toString();
        return new ActivityLogEntry(entry.id(), text, entry.summary(), entry.summary(), entry.detail(), filed);
    }
}
