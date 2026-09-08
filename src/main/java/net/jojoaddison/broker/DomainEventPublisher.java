package net.jojoaddison.broker;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.jojoaddison.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code entity.created} events to {@code hc.professional.entity}
 * for the admin portal (professional-onboarding-workflow.md § Domain events).
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
    private static final String SOURCE = "hc-professional-service";

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final StreamBridge streamBridge;
    private final boolean enabled;

    public DomainEventPublisher(StreamBridge streamBridge, ApplicationProperties properties) {
        this.streamBridge = streamBridge;
        this.enabled = properties.getKafka().isEnabled();
        if (!enabled) {
            log.info(
                "Domain event publishing is disabled (application.kafka.enabled=false); " +
                "entity.created and compliance.alert will not be emitted"
            );
        }
    }

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
     * <h2>{@code accountId} is the gateway's {@code User.id}, which is not what this database stores</h2>
     *
     * <p><b>This is the one place the two meanings of that name have to be held apart.</b> The
     * specified contract names {@code accountId} and means the gateway's {@code User.id}. This
     * service resolves its caller from the JWT subject, so what
     * {@code OnboardingResource.currentAccountId()} returns — and what {@code Profile.accountId}
     * stores — is the <b>login</b>. Two different values wearing one name.
     *
     * <p>The payload takes the specification's meaning. Until 2026-09-08 it did the opposite: it
     * published the login under {@code accountId} and added the {@code User.id} as an eighth field
     * called {@code accountUid}, naming the specified field after the value this database happens to
     * hold rather than after the value the estate asked for. Renaming the payload key does <b>not</b>
     * rename {@code Profile.accountId}, which 54 call sites key off — that is item 48's migration,
     * and it is now required rather than declined; see below.
     *
     * <h2>{@code accountId} is the only join, and this half often does not have it yet</h2>
     *
     * <p>The estate's decision is that the account identifier correlates a professional and
     * <b>nothing else does</b> — which is what hc-admin's {@code SiblingDomainEvent} has said all
     * along: <i>"the correlation key: lowercased email for a patient, {@code accountId} for a
     * professional."</i> The login used to ride in {@code subject.login} beside it and was the join
     * that worked; it is gone, because two join keys is two answers to "is this the same clinician",
     * and a login is editable in user management.
     *
     * <p><b>So a frame whose {@code accountId} is null cannot be placed by any consumer.</b> This
     * service learns {@code User.id} only when a clinician saves their own profile with a
     * uid-bearing token, so that is every profile written before 2026-09-07 and every one whose owner
     * has not signed in since. Until those are backfilled this half announces status for clinicians
     * hc-admin cannot identify — and the Kafka key is null for them too, so their frames are not even
     * ordered against each other. Both costs are paid off by backfilling {@code User.id}, not by
     * reintroducing a second identifier.
     *
     * <p>{@code lastModifiedBy} is an <b>account identifier and never a display name</b>:
     * {@code SpringSecurityAuditorAware} fills it from the JWT subject, so today it holds a login for
     * the same reason — item 48 again. The name a directory displays comes from the account half;
     * nothing here duplicates it.
     *
     * <p>Nothing is nulled and nothing is synthesised. Recorded in backlog.md items 47 § 2b and 48.
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
        // `accountId` IS THE GATEWAY'S User.id, which is what the specified contract names — not this
        // service's stored Profile.accountId, which holds the login. Those are two different values
        // wearing one name, and the payload takes the spec's meaning.
        //
        // This field was published as `accountUid` beside a login-valued `accountId` until 2026-09-08.
        // That was an eighth field on a seven-field contract, and it named the specified field after
        // the value this database happens to store rather than after the value the estate asked for.
        // The login has not been lost: it travels in `subject.login`, which is what hc-admin actually
        // correlates the two phases on — see backlog item 48. Renaming the payload key does NOT rename
        // Profile.accountId, which 54 call sites key off; that is item 48's declined migration and is
        // recorded there.
        //
        // OMITTED when unknown, never present-and-null. TokenProvider omits the `uid` claim when blank
        // on the argument that a present-and-empty value is one a reader can compare against stored
        // data and match something, while an absent one is the only unambiguous "not known". For the
        // month after this ships it is unknown on nearly every row, so a present null would be the
        // shape most consumers meet first.
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
            // CONSEQUENCE, AND IT IS NOT SMALL: this service only learns User.id when a clinician
            // saves their own profile with a uid-bearing token, so accountId is NULL — and the frame
            // therefore unjoinable — for every profile written before 2026-09-07 and every one whose
            // owner has not signed in since. Until those are backfilled, phase 2 announces status for
            // clinicians hc-admin cannot place. See backlog item 48: the migration it declined is now
            // required, not optional.
            new ProfessionalEvent.Subject(null, accountId),
            data
        );
        // KEYED ON accountId, the same value and the same space the gateway keys its frames on, so a
        // clinician's account half and profile half land on one partition and stay ordered against
        // each other. Keying on anything else would reintroduce the second identifier space this
        // change exists to remove.
        //
        // It can be NULL, and a null key is round-robin: no ordering guarantee for that clinician's
        // frames until the uid is learnt, and a partition move at the moment it is. That is a real
        // cost and it is the same blocker as the unjoinable subject above — both are paid off by
        // backfilling User.id rather than by choosing a different key here.
        publishShared(ONBOARDING_STATE_BINDING, ProfessionalEventType.PROFILE_STATUS, accountId, event, "profile " + profileId);
    }

    /**
     * {@link #publish} for the estate-shaped envelope.
     *
     * <p>A near-twin rather than a generalisation of the existing one, because the existing one is
     * typed to {@link DomainEventEnvelope} and widening it to {@code Object} would remove the only
     * thing stopping an arbitrary payload reaching these bindings. Both honour
     * {@code application.kafka.enabled=false} the same way — returning <em>before</em> the send, so
     * no binding is created and {@code BindingService} never enters its retry loop.
     */
    private void publishShared(String binding, String eventType, String key, ProfessionalEvent envelope, String subject) {
        if (!enabled) {
            log.debug("Skipping {} for {} — publishing disabled", eventType, subject);
            return;
        }
        try {
            MessageBuilder<ProfessionalEvent> message = MessageBuilder.withPayload(envelope);
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
