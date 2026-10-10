package net.jojoaddison.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jojoaddison.broker.AccountDetailsEvent;
import net.jojoaddison.broker.EntityChangeEvent;
import net.jojoaddison.broker.ProfessionalEventType;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.dto.OnboardingProgressDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps one clinician's completion meter live: records the gateway's step-1 verdict, and pushes a
 * freshly computed meter down that clinician's own SSE stream whenever anything behind the meter
 * changes (backlog.md row 230, unit A, decision 2).
 *
 * <h2>What it consumes, and why it is two bindings</h2>
 *
 * <table>
 *   <caption>The two channels this reads</caption>
 *   <tr><th>binding</th><th>topic</th><th>frames</th><th>what it does</th></tr>
 *   <tr><td>{@code meterAccountEvents-in-0}</td><td>{@code hc.professional.registration}</td>
 *       <td>{@code AccountDetailsUpdated}</td>
 *       <td>records step 1 in {@code AccountCompleteness}, then pushes</td></tr>
 *   <tr><td>{@code meterEntityEvents-in-0}</td><td>{@code professional.event}</td>
 *       <td>{@code EntityChanged} for {@code Profile}, {@code PersonalDocument},
 *           {@code ProfessionalApplication}</td>
 *       <td>pushes; steps 2–4 are already in this database</td></tr>
 * </table>
 *
 * <p>Two bindings because they are two topics, and they are two topics because the frames come from
 * two applications: row 230 puts the account event on {@code hc.professional.registration} as an
 * extension of {@code RegistrationEventPublisher}, which is where that clinician's other three frames
 * already ride under the same {@code accountId} key.
 *
 * <p><b>Steps 2, 3 and 4 need no new producer.</b> {@code EntityChangeAnnouncer} already announces
 * every document this service writes or removes, in every collection, on {@code professional.event} —
 * so {@code Profile}, {@code PersonalDocument} and {@code ProfessionalApplication} changes arrive
 * whatever path wrote them, including from a scheduler, a migration or the compliance sweep. A list of
 * publish sites would have been the alternative and this estate has paid for that three times; see
 * that class.
 *
 * <h2>⭐ It recomputes rather than accumulating, and that is not an optimisation</h2>
 *
 * <p>The frame says <em>that</em> a row changed and carries no values — identifiers and metadata only,
 * which is the rule on both channels and the reason hc-admin can read {@code professional.event} as an
 * audit trail. So this consumer cannot apply a delta even if it wanted to: it resolves the account the
 * change belongs to and asks {@code OnboardingService.progressFor}, which computes the whole meter from
 * the database. Three things follow and all three are wanted. A missed frame costs a refresh and not a
 * wrong number. A replay cannot drift the meter, because there is no accumulated state to drift. And
 * the {@code GET} and the push are the <b>same computation</b>, so the two cannot disagree — which is
 * what makes the {@code GET} authoritative in the sense row 230 requires.
 *
 * <h2>⛔ It must not write to this database beyond the one projection</h2>
 *
 * <p>{@code EntityChangeAnnouncer}'s javadoc states the hazard: <i>"a future consumer of
 * {@code professional.event} that writes back into this database: that loop has a broker in the middle,
 * so it does not announce itself as a stack overflow, it announces itself as traffic."</i> This
 * consumer writes exactly one collection, {@code account_completeness}, and only from the
 * <em>registration</em> binding — and that collection is excluded from the announcer, so the loop does
 * not exist. Anything added here that persists a document must be excluded there in the same change.
 *
 * <h2>Dedupe, and the failure mode a consumer must not have</h2>
 *
 * <p>Delivery is at-least-once, so {@link #seen} is a bounded in-memory window over {@code eventId} —
 * {@code MessageEventConsumer}'s shape, and the right one here for the same reason: it is a
 * de-duplication window over live sockets, not a durability mechanism, and a restart legitimately
 * forgets it. ⚠ Deduping matters <em>less</em> here than there, because recomputing is idempotent; what
 * it saves is four repository reads and a socket write per redelivery, not a wrong answer.
 *
 * <p>⚠ <b>Nothing here propagates.</b> A consumer that throws costs the binding — the frame is retried,
 * then the container logs and may stop — so a meter refresh would take down the push notifications
 * sharing this service. Both publishers here already catch rather than propagate; the same rule runs in
 * this direction. A frame this consumer cannot use is counted in a log line and dropped.
 *
 * <p>In {@code ..service..} with its Spring Cloud Function beans declared in
 * {@code MeterStreamConfiguration}, for the reason {@code PushNotificationConfiguration} records:
 * {@code TechnicalStructureTest} allows Service to be reached from Web and Config only, so a
 * {@code @Component} in {@code ..broker..} may not reach the services this needs.
 */
@Service
public class MeterConsumer {

    private static final Logger log = LoggerFactory.getLogger(MeterConsumer.class);

    /**
     * The entity types whose changes can move a step of the meter, by simple name — which is what
     * {@code EntityChangeEvent.Subject.entityType} carries.
     *
     * <p>⚠ <b>A positive list, and a frame outside it is ignored rather than acted on.</b>
     * {@code professional.event} carries every collection in this service — tasks, teams, duty
     * rosters, categories, the lot — and recomputing a meter for each would be four repository reads
     * per write in the deployment for a number that cannot have changed.
     *
     * <p><b>{@code Address} and {@code EmergencyContact} are deliberately absent</b> and their
     * omission is not a gap: both are embedded in the {@code Profile} document rather than stored in
     * collections of their own, so a change to either <em>is</em> a {@code Profile} save and arrives
     * under that name. An entry for them would match nothing.
     */
    static final Set<String> METERED_ENTITY_TYPES = Set.of(
        Profile.class.getSimpleName(),
        PersonalDocument.class.getSimpleName(),
        ProfessionalApplication.class.getSimpleName()
    );

    private static final int SEEN_CAPACITY = 5000;

    /** Bounded LRU of eventIds already applied. Access is synchronised; volume here is tiny. */
    private final Set<String> seen = Collections.newSetFromMap(
        Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_CAPACITY;
                }
            }
        )
    );

    private final OnboardingService onboardingService;
    private final OnboardingProgressStream stream;
    private final ProfileRepository profileRepository;
    private final PersonalDocumentRepository personalDocumentRepository;
    private final ProfessionalApplicationRepository applicationRepository;

    public MeterConsumer(
        OnboardingService onboardingService,
        OnboardingProgressStream stream,
        ProfileRepository profileRepository,
        PersonalDocumentRepository personalDocumentRepository,
        ProfessionalApplicationRepository applicationRepository
    ) {
        this.onboardingService = onboardingService;
        this.stream = stream;
        this.profileRepository = profileRepository;
        this.personalDocumentRepository = personalDocumentRepository;
        this.applicationRepository = applicationRepository;
    }

    /**
     * The gateway's step-1 verdict: record it, then push the recomputed meter.
     *
     * <p>Frames of any other type — and {@link DomainEventEnvelope}-shaped frames, which deserialise
     * with a {@code null} {@code type} — are ignored rather than logged as errors: the topic is shared
     * by design and carries six types from two producers.
     *
     * <p>⚠ <b>The push happens only when the projection actually moved.</b>
     * {@code recordAccountCompleteness} refuses a frame older than the stored observation, and pushing
     * after a refused frame would send a meter that is identical to the last one — harmless, but it
     * would also mean a redelivery storm became a socket-write storm.
     */
    public void onAccountDetails(AccountDetailsEvent event) {
        try {
            if (event == null || !ProfessionalEventType.ACCOUNT_DETAILS_UPDATED.equals(event.type())) {
                return;
            }
            if (isRedelivery(event.eventId())) {
                return;
            }
            String accountId = event.accountId();
            Boolean complete = event.detailsComplete();
            if (accountId == null || complete == null) {
                // Deliberately not treated as "incomplete": an unreadable frame applied as false
                // would move step 1 backwards and leave it there until the account was next written.
                log.warn("Ignoring {} {} — it names no account or carries no verdict", event.type(), event.eventId());
                return;
            }
            if (onboardingService.recordAccountCompleteness(accountId, complete, event.occurredAt())) {
                pushTo(accountId);
            }
        } catch (RuntimeException e) {
            // Counted and named, never propagated: a throwing consumer costs the binding, and the
            // push notifications on this service share the broker with it.
            log.error("Could not apply an account-details event to the completion meter", e);
        }
    }

    /**
     * A document in this service changed: if it is one of the metered types, resolve whose it is and
     * push that clinician a recomputed meter.
     *
     * <p>⚠ <b>A {@code DELETED} frame for a metered type cannot be resolved and that is accepted.</b>
     * The frame carries the row's id and the row is gone, so there is nothing left to read the
     * {@code accountId} or the {@code profileId} off. The consequence is bounded by what deletes
     * anything here: {@code PersonalDocument} rows are <b>archived rather than deleted</b> (backlog.md
     * item 20), which is a save and resolves normally, and nothing in {@code web.rest} deletes a
     * {@code Profile} or a {@code ProfessionalApplication} outside the entity CRUD an administrator
     * reaches. Such a clinician's next {@code GET} is correct — the meter is computed from the
     * database — so what is lost is the live refresh, which is exactly the degradation row 230 accepts
     * for a broker outage.
     */
    public void onEntityChange(EntityChangeEvent event) {
        try {
            if (event == null || event.subject() == null || !METERED_ENTITY_TYPES.contains(event.subject().entityType())) {
                return;
            }
            if (isRedelivery(event.eventId())) {
                return;
            }
            accountIdFor(event.subject().entityType(), event.subject().entityId()).ifPresentOrElse(
                this::pushTo,
                () ->
                    log.debug(
                        "No account resolves from {} {} — nothing to refresh",
                        event.subject().entityType(),
                        event.subject().entityId()
                    )
            );
        } catch (RuntimeException e) {
            log.error("Could not apply an entity change to the completion meter", e);
        }
    }

    /**
     * Whose meter a changed row belongs to.
     *
     * <p>⚠ <b>Resolved from the row, never from {@code data.actorAccountId}.</b> The actor is whoever
     * made the write, which for a reviewer verifying a document or an administrator editing a profile
     * is a different person from the subject — so keying on it would refresh the wrong clinician's
     * stream, and refresh nothing for the one whose meter moved. {@code PersonalDocument} needs two
     * hops because it names a profile and the profile names the account.
     */
    private Optional<String> accountIdFor(String entityType, String entityId) {
        if (entityId == null) {
            return Optional.empty();
        }
        if (Profile.class.getSimpleName().equals(entityType)) {
            return profileRepository.findById(entityId).map(Profile::getAccountId);
        }
        if (ProfessionalApplication.class.getSimpleName().equals(entityType)) {
            return applicationRepository.findById(entityId).map(ProfessionalApplication::getAccountId);
        }
        if (PersonalDocument.class.getSimpleName().equals(entityType)) {
            return personalDocumentRepository
                .findById(entityId)
                .map(PersonalDocument::getProfileId)
                .flatMap(profileRepository::findById)
                .map(Profile::getAccountId);
        }
        return Optional.empty();
    }

    /**
     * Computes the meter from the database and pushes it to that account's own streams.
     *
     * <p>Skipped when nobody is connected, which is the ordinary case — so an applicant who is not
     * looking at the page costs nothing but the resolve above.
     */
    private void pushTo(String accountId) {
        if (accountId == null || stream.openStreams(accountId) == 0) {
            return;
        }
        OnboardingProgressDTO progress = onboardingService.progressFor(accountId);
        stream.push(accountId, progress);
    }

    /**
     * Whether this frame has already been applied.
     *
     * <p>A frame with no {@code eventId} is <b>applied</b> rather than dropped: recomputing is
     * idempotent, so the cost of a duplicate is four reads and a socket write, where dropping a frame
     * that merely lacks an id would cost a refresh for no benefit.
     *
     * <h2>⚠ "Already applied" is not quite what it says, and this is the only way the projection can
     * get stuck</h2>
     *
     * <p><b>The id is marked seen BEFORE the work runs, and both callers swallow their failures.</b>
     * So a frame whose handling throws — a transient Mongo failure, say — is counted as applied, the
     * offset commits, and <b>a replay of that same frame is deduped away</b>. Step 1 then keeps
     * whatever value it had until the account is next written.
     *
     * <p>⭐ <b>Worth knowing exactly how far that goes, because the direction matters.</b> This
     * projection <em>cannot</em> claim complete when a clinician is not: a stale {@code true} arriving
     * after a {@code false} is refused by {@code OnboardingService.recordAccountCompleteness}'s
     * ordering guard, and the null-{@code occurredAt} path that would disable that guard is itself
     * refused. What it <em>can</em> do is stay {@code false} for ever, three ways — no backfill exists
     * for an account unwritten since row 230 shipped; the gateway's {@code deleteUser} publishes
     * nothing, so a removed account keeps an orphan row; and this one. <b>All three are a tick that
     * does not appear, never a tick that appears wrongly</b>, and nothing gates on the value. That is
     * why fail-closed was the safe default rather than merely the tidy one.
     *
     * <p>⛔ <b>Not fixed here, deliberately, and the fix is one line.</b> Moving {@code seen.add} to
     * after the work would make a failed frame retryable, and recomputing is idempotent so
     * re-application costs nothing — but it also means a <em>permanently</em> failing frame is retried
     * on every redelivery for ever, and it changes what this window means, which nothing in row 230
     * asked for. Raised with the owner rather than taken: it is one boolean on a surface that gates
     * nothing, and it belongs in a row beside {@code deleteUser}'s orphan.
     */
    private boolean isRedelivery(String eventId) {
        if (eventId == null) {
            return false;
        }
        if (!seen.add(eventId)) {
            log.debug("Ignoring redelivered {}", eventId);
            return true;
        }
        return false;
    }
}
