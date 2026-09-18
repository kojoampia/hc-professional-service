package net.jojoaddison.broker;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.jojoaddison.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code entity.created} events to {@code hc.professional.entity}
 * for the admin portal (professional-onboarding-workflow.md § Domain events), and — since
 * backlog.md item 141 — every document change to {@code professional.event}; see
 * {@link #publishEntityChange}, which is the one method here that does not run on its caller's
 * thread.
 * Records are keyed by entityId so per-entity ordering holds; delivery is
 * at-least-once and consumers dedupe on eventId. Publishing must never break
 * the write path — failures are logged, not propagated.
 * <p>
 * Deployments that run no broker set {@code application.kafka.enabled=false}; see
 * {@link ApplicationProperties.Kafka} for why the absence of Kafka has to be stated rather than
 * discovered.
 */
@Component
public class DomainEventPublisher {

    public static final String ENTITY_TOPIC_BINDING = "entityEvents-out-0";
    public static final String ONBOARDING_STATE_BINDING = "onboardingStateEvents-out-0";

    /**
     * The binding behind {@code professional.event} — backlog.md item 141.
     *
     * <p><b>It is declared in {@code config/application.yml} and must stay declared.</b> StreamBridge
     * creates a <em>dynamic</em> destination for a binding it cannot find, so a misspelling here does
     * not fail: it silently opens a topic named after the typo, which nothing consumes and nothing
     * reports. hc-admin shipped exactly that as {@code roster-events} and it published into nowhere
     * until somebody went looking. {@code EntityChangeBindingTest} pins this constant against the
     * shipped YAML and against the literal {@code professional.event}, which is a cross-product name
     * and therefore earns an enumeration where an internal one would not.
     */
    public static final String ENTITY_CHANGE_BINDING = "entityChangeEvents-out-0";

    private static final String SOURCE = "hc-professional-service";

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final StreamBridge streamBridge;
    private final boolean enabled;
    private final Executor entityChangeExecutor;

    @Autowired
    public DomainEventPublisher(StreamBridge streamBridge, ApplicationProperties properties) {
        this(streamBridge, properties, entityChangeExecutor());
    }

    /**
     * Test seam: the same publisher with a caller-supplied executor, so a unit test can pass
     * {@code Runnable::run} and assert on the frame without racing a background thread.
     */
    DomainEventPublisher(StreamBridge streamBridge, ApplicationProperties properties, Executor entityChangeExecutor) {
        this.streamBridge = streamBridge;
        this.entityChangeExecutor = entityChangeExecutor;
        this.enabled = properties.getKafka().isEnabled();
        if (!enabled) {
            log.info(
                "Domain event publishing is disabled (application.kafka.enabled=false); " +
                "entity.created and compliance.alert will not be emitted"
            );
        }
    }

    /**
     * One thread, a bounded queue, and <b>{@link ThreadPoolExecutor.AbortPolicy}</b> — the shape
     * hc-admin's {@code OutboundEventPublisher} arrived at, for a reason this channel makes sharper
     * than any other.
     *
     * <p><b>Why the hop exists at all.</b> StreamBridge creates an output binding lazily, inside the
     * <em>first</em> {@code send()} for that destination, and that creation opens an AdminClient
     * bounded by {@code default.api.timeout.ms}. Against a broker that is not there it blocks for
     * <b>sixty seconds</b>, under a lock every later publisher then queues on — measured by hc-admin
     * at 60.6s for the first request and 15ms for the second, with the row written, a 201 returned
     * and nothing failing. The other publishers on this class ride request handlers, so that cost
     * lands on one endpoint. <b>This one rides a persistence callback, so it would land on every
     * write in the deployment</b>, including the first save of a startup runner.
     *
     * <p>⚠ <b>{@code CallerRunsPolicy} would silently restore the defect.</b> It is the conventional
     * choice for a bounded queue, it looks like back-pressure, and it hands the blocking send straight
     * back to the thread this exists to protect. {@code AbortPolicy} is deliberate and
     * {@code EntityChangeEventTest} pins it.
     *
     * <p>The queue is bounded rather than unbounded for the ordinary reason — an unbounded one turns a
     * dead broker into heap exhaustion — and the cost of the bound is stated rather than hidden: past
     * {@link #ENTITY_CHANGE_QUEUE} pending frames, a change is <b>dropped and logged as a warning</b>.
     * That is the right way round for an audit stream against a broker that is already failing; the
     * write it describes has been persisted either way, and losing the trail is recoverable where
     * losing the service is not.
     */
    static Executor entityChangeExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            1,
            // Nonzero because allowCoreThreadTimeOut below rejects a zero keep-alive outright
            // ("Core threads must have nonzero keep alive times"). The thread retires after a minute
            // of quiet rather than being held for the life of a service that may never write again.
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(ENTITY_CHANGE_QUEUE),
            runnable -> {
                Thread thread = new Thread(runnable, "hc-professional-entity-change");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * How many entity changes may be waiting on the broker before a further one is refused.
     *
     * <p>It is the <b>arriving</b> frame that is dropped, not the oldest queued one — that is what
     * {@link ThreadPoolExecutor.AbortPolicy} does, and it is the right way round here: the frames
     * already queued are older changes, and an audit trail that discards its history to make room for
     * the present is worse than one with a gap at the end that was logged.
     */
    static final int ENTITY_CHANGE_QUEUE = 1000;

    /**
     * Sends the envelope, or does nothing at all when publishing is disabled.
     * <p>
     * Returning before {@code streamBridge.send} is what makes this quiet: StreamBridge creates the
     * producer binding lazily, on first send, so never sending means the binding is never created
     * and {@code BindingService} never enters its 30-second retry loop. Suppressing the log without
     * suppressing the call would have fixed the per-write ERROR and left the retry loop running.
     */
    private void publish(String eventType, String entityId, DomainEventEnvelope envelope, String subject) {
        publish(ENTITY_TOPIC_BINDING, eventType, entityId, envelope, subject);
    }

    private void publish(String binding, String eventType, String key, DomainEventEnvelope envelope, String subject) {
        if (!enabled) {
            log.debug("Skipping {} for {} — publishing disabled", eventType, subject);
            return;
        }
        try {
            streamBridge.send(binding, MessageBuilder.withPayload(envelope).setHeader(KafkaHeaders.KEY, key.getBytes()).build());
        } catch (RuntimeException e) {
            log.error("Failed to publish {} for {}", eventType, subject, e);
        }
    }

    /**
     * Onboarding reached a new state the admin portal cares about.
     *
     * <p>Keyed by {@code accountId} rather than the application id, matching
     * {@code registration.created} on the same topic — the two together are one clinician's story,
     * and per-account ordering is what makes "IN_PROGRESS then COMPLETED" readable.
     *
     * <p>{@code state} is carried in the payload rather than the event type so a consumer switches
     * on one field instead of matching a family of event names.
     *
     * @param state one of {@code IN_PROGRESS}, {@code COMPLETED}, {@code ACTIVE}.
     */
    public void publishOnboardingState(String state, String accountId, String applicationId, String requestedRole, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("state", state);
        if (applicationId != null) {
            payload.put("applicationId", applicationId);
        }
        if (requestedRole != null) {
            payload.put("requestedRole", requestedRole);
        }
        DomainEventEnvelope envelope = new DomainEventEnvelope(
            UUID.randomUUID().toString(),
            "onboarding.state",
            Instant.now(),
            SOURCE,
            actor,
            payload
        );
        publish(ONBOARDING_STATE_BINDING, "onboarding.state", accountId, envelope, state + " " + accountId);
    }

    public void publishEntityCreated(String entityType, String entityId, String accountId, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityType", entityType);
        payload.put("entityId", entityId);
        if (accountId != null) {
            payload.put("accountId", accountId);
        }
        DomainEventEnvelope envelope = new DomainEventEnvelope(
            UUID.randomUUID().toString(),
            "entity.created",
            Instant.now(),
            SOURCE,
            actor,
            payload
        );
        publish("entity.created", entityId, envelope, entityType + " " + entityId);
    }

    /** WP7 compliance sweep: same topic and envelope, eventType {@code compliance.alert}. */
    public void publishComplianceAlert(String alertType, String entityId, String accountId, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertType", alertType);
        payload.put("entityId", entityId);
        if (accountId != null) {
            payload.put("accountId", accountId);
        }
        DomainEventEnvelope envelope = new DomainEventEnvelope(
            UUID.randomUUID().toString(),
            "compliance.alert",
            Instant.now(),
            SOURCE,
            actor,
            payload
        );
        publish("compliance.alert", entityId, envelope, alertType + " " + entityId);
    }

    /**
     * Messaging: one event per recipient of a new message, eventType {@code message.created}.
     * <p>
     * The payload is identifiers only — no subject, no body, no sender name. That is not a
     * formality here: it is what makes the second half of the flow necessary rather than optional.
     * A client learns only that something arrived for it and must then fetch the message through
     * the authorized read path, so the broker never carries clinical correspondence and a consumer
     * that should not see a message cannot see it by reading the topic.
     * <p>
     * Keyed by {@code recipientId} rather than message id, so everything destined for one account
     * lands on one partition and arrives in order.
     */
    public void publishMessageCreated(String messageId, String conversationId, String recipientId, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("conversationId", conversationId);
        payload.put("recipientId", recipientId);
        DomainEventEnvelope envelope = new DomainEventEnvelope(
            UUID.randomUUID().toString(),
            "message.created",
            Instant.now(),
            SOURCE,
            actor,
            payload
        );
        publish("message.created", recipientId, envelope, "message " + messageId + " for " + recipientId);
    }

    /**
     * The second half of a clinician's arrival: their profile exists, and this is the state of it.
     *
     * <p><b>Why this exists at all.</b> The gateway's {@code AccountCreated} says an account was
     * made and can say nothing about the person's profile, because at that moment there is none. A
     * directory on another stack cannot complete a record from the account half alone, and
     * hc-admin's own backlog (items 33, 35, 36) records nineteen links stuck in exactly that state.
     * This carries what registration could not. See {@link ProfessionalEventType} for the two-halves
     * argument and backlog.md item 47.
     *
     * <p><b>The topic is {@code hc.professional.registration}, not the entity topic</b>, reusing
     * {@link #ONBOARDING_STATE_BINDING} for the reason {@code onboarding.state} rides there: one
     * clinician's story is one topic, so a consumer following an account subscribes once. It is also
     * the only topic from this stack hc-admin is bound to.
     *
     * <h2>The payload is the architect's seven fields, and exactly those</h2>
     *
     * <p>{@code profileId}, {@code accountId}, {@code isComplete}, {@code isVerified},
     * {@code createdDate}, {@code modifiedDate}, {@code lastModifiedBy} — identifiers, two booleans
     * and three timestamps. <b>No role, no licence number, no name, no email.</b> That satisfies the
     * identifiers-only rule {@link DomainEventEnvelope} states outright, so no departure from it has
     * to be argued for.
     *
     * <h2>{@code accountId} is the gateway's {@code User.id}, and since item 50 so is the field</h2>
     *
     * <p><b>This used to be the one place the two meanings of that name had to be held apart.</b> The
     * specified contract names {@code accountId} and means the gateway's {@code User.id}, while this
     * service resolved its caller from the JWT subject, so {@code Profile.accountId} stored the
     * <b>login</b> — two different values wearing one name, and this payload took the
     * specification's meaning from an eighth field called {@code accountUid}. Backlog.md item 50
     * removed the fork: {@code SecurityUtils.getCurrentAccountId()} reads the {@code uid} claim,
     * {@code Profile.accountId} holds it, {@code AccountIdMigrationService} moved the stored values,
     * and {@code accountUid} is gone. <b>The wire contract did not change with it</b> — this key has
     * meant {@code User.id} since 2026-09-08 and still does; only where the value is read from did.
     *
     * <h2>{@code accountId} is the only join</h2>
     *
     * <p>The estate's decision is that the account identifier correlates a professional and
     * <b>nothing else does</b> — which is what hc-admin's {@code SiblingDomainEvent} has said all
     * along: <i>"the correlation key: lowercased email for a patient, {@code accountId} for a
     * professional."</i> The login used to ride in {@code subject.login} beside it; it is gone,
     * because two join keys is two answers to "is this the same clinician", and a login is editable
     * in user management.
     *
     * <p><b>A frame whose {@code accountId} is null still cannot be placed by any consumer</b>, and
     * that remains reachable rather than theoretical — a profile whose login resolved to no gateway
     * account is quarantined by the migration with its key cleared, exactly so that it announces
     * nothing it cannot substantiate. What has stopped being true is that it was the ordinary case:
     * before item 50 the field was null for every profile written before 2026-09-07 and every one
     * whose owner had not signed in since.
     *
     * <p>{@code lastModifiedBy} is an <b>audit value and never a lookup key</b>:
     * {@code SpringSecurityAuditorAware} fills it from the JWT subject, so it holds the login, and
     * that is deliberate — a person reading a trail needs a name rather than a Mongo id. Do not
     * "correct" it to the account id; nothing joins on it.
     *
     * <p>Nothing is nulled and nothing is synthesised. Recorded in backlog.md items 47 § 2b, 48 and 50.
     *
     * <p>One consequence is not softened: the halves are <b>not co-partitioned</b>, so nothing
     * orders this against the account events. It is a snapshot rather than a delta for exactly that
     * reason — applying it in any order, or twice, yields the same state.
     *
     * @param accountId the gateway's {@code User.id} for this clinician, and the only value a
     *                  consumer can correlate on. Null until the profile has learnt it, in which
     *                  case the frame is unjoinable and says so by omitting the key.
     * @param profileId this service's own id for the profile.
     * @param isComplete the profile's completeness, by {@code OnboardingService}'s single definition.
     * @param isVerified every live document verified, and at least one present.
     * @param createdDate when the profile row was created.
     * @param modifiedDate when it last changed.
     * @param lastModifiedBy an account identifier, never a display name.
     */
    public void publishProfileStatus(
        String accountId,
        String profileId,
        boolean isComplete,
        boolean isVerified,
        Instant createdDate,
        Instant modifiedDate,
        String lastModifiedBy
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profileId", profileId);
        // `accountId` IS THE GATEWAY'S User.id, which is what the specified contract names AND, since
        // backlog item 50, what Profile.accountId stores. The two used to be different values wearing
        // one name; the caller reads the field now rather than a shadow field called `accountUid`.
        //
        // OMITTED when unknown, never present-and-null. TokenProvider omits the `uid` claim when blank
        // on the argument that a present-and-empty value is one a reader can compare against stored
        // data and match something, while an absent one is the only unambiguous "not known". Still
        // reachable after item 50: a profile the migration could not resolve to a gateway account has
        // its key cleared rather than left holding a login, and announces nothing joinable — which is
        // the honest frame for a row that belongs to nobody.
        if (accountId != null) {
            data.put("accountId", accountId);
        }
        data.put("isComplete", isComplete);
        data.put("isVerified", isVerified);
        data.put("createdDate", createdDate);
        data.put("modifiedDate", modifiedDate);
        data.put("lastModifiedBy", lastModifiedBy);
        ProfessionalEvent event = new ProfessionalEvent(
            UUID.randomUUID().toString(),
            ProfessionalEventType.PROFILE_STATUS,
            ProfessionalEvent.VERSION,
            Instant.now(),
            SOURCE,
            // Email null from this service — the payload rule.
            //
            // accountId ONLY. It is the gateway's User.id and the estate's sole correlation key for a
            // professional, which hc-admin's SiblingDomainEvent has stated all along. The login rode
            // here until 2026-09-08 and was the join that worked; it is gone because two join keys is
            // two answers to "is this the same clinician", and a login is editable.
            //
            // It is no longer usually null: backlog item 50 made Profile.accountId hold this very
            // value and migrated the stored rows, so a frame carrying no subject now means a profile
            // that genuinely belongs to no gateway account rather than one this service has not been
            // told about yet.
            new ProfessionalEvent.Subject(null, accountId),
            data
        );
        // KEYED ON accountId, the same value and the same space the gateway keys its frames on, so a
        // clinician's account half and profile half land on one partition and stay ordered against
        // each other. Keying on anything else would reintroduce the second identifier space this
        // change exists to remove.
        //
        // It can still be NULL, and a null key is round-robin: no ordering guarantee for that
        // clinician's frames. After item 50 that is only reachable for a quarantined profile, which
        // has no account to order against anyway — not, as before, for every clinician who had not
        // signed in since 2026-09-07.
        publishShared(ONBOARDING_STATE_BINDING, ProfessionalEventType.PROFILE_STATUS, accountId, event, "profile " + profileId);
    }

    /**
     * One document changed — the whole of {@code professional.event}, backlog.md item 141.
     *
     * <h2>Five fields, because one audit row needs five things — and where each one sits</h2>
     *
     * <p>hc-admin's item 109 turns each frame into a single {@code AuditLog} row and needs exactly:
     * <b>what kind of thing</b> ({@code entityType}), <b>which one</b> ({@code entityId}),
     * <b>what happened</b> ({@code action}), <b>when</b> — the envelope's {@code occurredAt}, not a
     * sixth data key — and <b>who</b> ({@code actorAccountId}).
     *
     * <p><b>The first two ride {@code subject} and the last two ride {@code data}</b>, per hc-admin's
     * item 124, decided by the architect on 2026-09-18:
     *
     * <pre>
     * subject : { entityType, entityId }
     * data    : { action, actorAccountId }
     * </pre>
     *
     * <p>All four of these keys sat in {@code data} until then, with {@code subject} left null — one
     * of the three shapes the estate's four producers shipped under one {@code type} on the day they
     * were all built. See {@link EntityChangeEvent} for the decision and for the two readings it
     * rejected.
     *
     * <p>⛔ <b>Nothing else may be added to this payload, and in particular no changed values.</b> The
     * rule has two independent origins that happen to agree: {@link DomainEventEnvelope} states
     * identifiers-only for this subsystem, and hc-admin's item 110 arrives at the same payload from
     * the other side, because a channel carrying document contents rebuilds the local mirror their
     * item 107 exists to delete. A field-level diff here would also be the one thing on this wire
     * capable of carrying a card number or a home address, since this callback fires for
     * {@code Profile} like it fires for everything else.
     *
     * <h2>The actor is an {@code accountId}, and that is not the value the auditor holds</h2>
     *
     * <p><b>This is the trap this subsystem has already sprung once.</b> {@code ProfileStatus} carries
     * a {@code lastModifiedBy} that the written contract calls an account identifier and that
     * {@code SpringSecurityAuditorAware} in fact fills from the JWT <em>subject</em> — the login. That
     * is harmless there and is documented as such, because the field is an audit value hc-admin
     * displays verbatim and joins to nothing. <b>It would not be harmless here.</b> This field is the
     * actor on rows hc-admin stores rather than renders, and item 107 D1 makes {@code accountId} the
     * estate's join key, so a login arriving under that name is a value that looks joinable, is not,
     * and disagrees with every other product's answer to "who did this".
     *
     * <p>So the caller resolves this from {@link net.jojoaddison.security.SecurityUtils#getCurrentAccountId()},
     * which reads the {@code uid} claim and filters on the minting issuer — never from
     * {@code getCurrentUserLogin()}. A login on this channel is a defect and
     * {@code EntityChangeEventTest} fails on one.
     *
     * <p><b>Absent rather than {@code "system"} when there is no account behind the write.</b> A
     * scheduler, a startup runner, a Kafka consumer and a migration all write with no security
     * context, and a token minted by hc-admin or hc-patient resolves to nobody here by design. The
     * key is then omitted, following {@link #publishProfileStatus}'s rule that an unknown identifier
     * is left out rather than sent present-and-null: a literal in an identifier space is a value a
     * consumer can compare against stored data and match, where an absent one is the only unambiguous
     * "no account did this".
     *
     * <h2>Keyed on the entity, and about no clinician in particular</h2>
     *
     * <p>The Kafka key is {@code entityId}, so every frame for one document lands on one partition and
     * an audit trail reads {@code CREATED} before the {@code UPDATED} that followed it. That is the
     * same rule {@code entity.created} already follows on the other topic.
     *
     * <p><b>The subject names the row, and it is an {@link EntityChangeEvent.Subject} rather than a
     * {@link ProfessionalEvent.Subject}.</b> That is the part worth reading twice: this event is about
     * a <em>document</em>, and most of the collections it fires for have no clinician behind them at
     * all, so the clinician-shaped subject the registration topic uses cannot describe it. The two
     * records exist so that one channel can mean "the row" while the other goes on meaning "the
     * person" — see {@link EstateEventEnvelope}.
     *
     * <p>⛔ <b>The actor does not go in {@code subject}.</b> That was one of the two readings item 124
     * rejected, and it would render "who did this" in the field the rest of the estate uses for "who
     * this is about" — the same category error as the {@code lastModifiedBy} one above, one field
     * along. It lives in {@code data.actorAccountId} and nowhere else.
     *
     * <p>Publishing is handed to {@link #entityChangeExecutor} and never runs on the caller's thread;
     * see that method for the sixty seconds this is avoiding. The caller must therefore resolve the
     * actor and the timestamp <b>before</b> calling, because neither the security context nor "now"
     * survives the hop.
     *
     * @param entityType the document's simple class name, e.g. {@code Profile}.
     * @param entityId the document's own id.
     * @param action which of created, updated or deleted; see {@link EntityChangeAction} for the
     *     limit on telling the first two apart.
     * @param actorAccountId the gateway's {@code User.id} for whoever caused the write, or null when
     *     no account did — never a login, and never a placeholder.
     * @param occurredAt when the change happened, read on the calling thread.
     */
    public void publishEntityChange(
        String entityType,
        String entityId,
        EntityChangeAction action,
        String actorAccountId,
        Instant occurredAt
    ) {
        if (!enabled) {
            log.debug("Skipping {} {} {} — publishing disabled", action, entityType, entityId);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", action.name());
        // OMITTED, not "system" and not null — see the javadoc. Absence is the only unambiguous way
        // to say that no account was behind this write.
        if (actorAccountId != null) {
            data.put("actorAccountId", actorAccountId);
        }
        EntityChangeEvent event = new EntityChangeEvent(
            UUID.randomUUID().toString(),
            ProfessionalEventType.ENTITY_CHANGED,
            EntityChangeEvent.VERSION,
            occurredAt,
            SOURCE,
            // THE SUBJECT IS THE RECORD THAT CHANGED — hc-admin item 124, decided 2026-09-18. These
            // two keys sat in `data` with the subject left null until then, which was one of three
            // shapes the four products shipped under one `type`. See EntityChangeEvent.
            new EntityChangeEvent.Subject(entityType, entityId),
            data
        );
        String description = action + " " + entityType + " " + entityId;
        try {
            entityChangeExecutor.execute(
                () -> publishShared(ENTITY_CHANGE_BINDING, ProfessionalEventType.ENTITY_CHANGED, entityId, event, description)
            );
        } catch (RejectedExecutionException e) {
            // The bound, doing its job. Loud, because a gap in an audit trail that nobody was told
            // about is indistinguishable from a period in which nothing happened.
            log.warn("Dropped {} — the entity-change publish queue is full, so the broker is not keeping up", description);
        }
    }

    /**
     * {@link #publish} for the estate-shaped envelope.
     *
     * <p>A near-twin rather than a generalisation of the existing one, because the existing one is
     * typed to {@link DomainEventEnvelope} and widening it to {@code Object} would remove the only
     * thing stopping an arbitrary payload reaching these bindings. Both honour
     * {@code application.kafka.enabled=false} the same way — returning <em>before</em> the send, so
     * no binding is created and {@code BindingService} never enters its retry loop.
     *
     * <p>The parameter is {@link EstateEventEnvelope} rather than either record, because the two
     * estate-shaped envelopes disagree about what {@code subject} means and must stay separate types
     * — see that interface. It is <b>sealed</b>, so this stays a closed list rather than the
     * {@code Object} the paragraph above rules out.
     */
    private void publishShared(String binding, String eventType, String key, EstateEventEnvelope envelope, String subject) {
        if (!enabled) {
            log.debug("Skipping {} for {} — publishing disabled", eventType, subject);
            return;
        }
        try {
            MessageBuilder<EstateEventEnvelope> message = MessageBuilder.withPayload(envelope);
            if (key == null) {
                // No key rather than a null one. `key.getBytes()` NPEs, and the catch below would
                // swallow it and log "Failed to publish" — a missing identifier reported as a broker
                // fault, which sends you to Kafka to debug a data problem. The cost of no key is
                // round-robin partitioning, so this clinician's frames are unordered against each
                // other; they are also unjoinable, which is the larger problem and is item 50's.
                log.warn("Publishing {} for {} with no partition key — no accountId yet, see backlog item 50", eventType, subject);
            } else {
                message.setHeader(KafkaHeaders.KEY, key.getBytes(StandardCharsets.UTF_8));
            }
            streamBridge.send(binding, message.build());
        } catch (RuntimeException e) {
            log.error("Failed to publish {} for {}", eventType, subject, e);
        }
    }
}
