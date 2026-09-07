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
     * <h2>The payload is the architect's seven fields, plus one identifier</h2>
     *
     * <p>{@code profileId}, {@code accountId}, {@code isComplete}, {@code isVerified},
     * {@code createdDate}, {@code modifiedDate}, {@code lastModifiedBy} — identifiers, two booleans
     * and three timestamps — and, since 2026-09-07, {@code accountUid}. The eighth is the second
     * half of the identifier fork described below and nothing else: <b>an identifier this service
     * was given, not a fact about the clinician</b>, so it changes nothing about what the payload
     * discloses. A consumer written against the seven ignores it. <b>No role, no licence number, no
     * name, no email.</b> That satisfies the
     * identifiers-only rule {@link DomainEventEnvelope} states outright, so no departure from it has
     * to be argued for. {@code lastModifiedBy} is an <b>account identifier and never a display
     * name</b>: {@code SpringSecurityAuditorAware} fills it from the JWT subject, the same space as
     * {@code accountId}.
     *
     * <p>The name a directory displays comes from the account half and is joined on
     * {@code accountId}. Nothing here duplicates it.
     *
     * <h2>Two identifiers, because the two producers mean different things by {@code accountId}</h2>
     *
     * <p><b>The two producers on this topic do not mean the same thing by {@code accountId}, and
     * have not since WP3.</b> The gateway keys and publishes {@code User.id}. This service resolves
     * its caller from the JWT subject, so what {@code OnboardingResource.currentAccountId()} returns
     * — and what this class has always called {@code accountId} — is the <b>login</b>.
     * {@code onboarding.state} has been landing under a different key from the gateway's frames
     * about the same clinician all along.
     *
     * <p><b>Item 48 did not resolve that by moving either side onto the other's identifier.</b> The
     * stored value here is still the login, because every identifier in this database is one and
     * they cannot all be rewritten — {@code Profile.accountUid} states the reasoning in full. What
     * changed is that the frame now names <b>both</b> identifiers, so a consumer can join on either:
     *
     * <ul>
     *   <li>{@code subject.login} and {@code data.accountId} — the login. <b>Always populated, and
     *       the join that works today</b>, since {@code AccountCreated} carries the same value under
     *       {@code subject.login}. This is the correlation key until the {@code uid} claim has been
     *       live longer than a remember-me token.</li>
     *   <li>{@code data.accountUid} — the gateway's {@code User.id}, which is what the account half
     *       keys and partitions by. <b>Null until that clinician has saved their own profile with a
     *       token carrying the claim</b>, which is every profile written before 2026-09-07 and any
     *       whose owner has not signed in since. Absent means "not known"; it never means "no
     *       account", and it is never guessed.</li>
     * </ul>
     *
     * <p>Nothing is nulled and nothing is synthesised: both identifiers are published exactly as
     * this service holds them, and the one that is unknown says so. Recorded in backlog.md items 47
     * § 2b and 48.
     *
     * <p>One consequence is not softened: the halves are <b>not co-partitioned</b>, so nothing
     * orders this against the account events. It is a snapshot rather than a delta for exactly that
     * reason — applying it in any order, or twice, yields the same state.
     *
     * @param accountId this service's account identifier for the clinician — the login. Always
     *                  present, and the field the two halves agree on. See {@code Profile.accountId}.
     * @param accountUid the gateway's {@code User.id} for the same clinician, or null when it is not
     *                   known. <b>Must come off the profile row, never off the calling token</b>:
     *                   three of the four paths in here are an administrator acting on somebody
     *                   else's profile. See {@code Profile.accountUid}.
     * @param isComplete the profile's completeness, by {@code OnboardingService}'s single definition
     *                   — the same one the transition to {@code ACTIVE} is gated on, not a second
     *                   derivation of it.
     * @param isVerified every live document on the profile verified, and at least one present, by
     *                   the same rule the approval gate applies.
     * @param lastModifiedBy an account identifier, never a name.
     */
    public void publishProfileStatus(
        String accountId,
        String accountUid,
        String profileId,
        boolean isComplete,
        boolean isVerified,
        Instant createdDate,
        Instant modifiedDate,
        String lastModifiedBy
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profileId", profileId);
        data.put("accountId", accountId);
        data.put("accountUid", accountUid);
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
            // Email null from this service — the payload rule. The subject repeats the accountId the
            // data carries, so the envelope names its own subject like every other frame on the topic.
            //
            // `login` used to be left off here, on the reasoning that it is the same string as
            // accountId and a second copy of one value under two names is something a consumer
            // eventually disagrees with itself about. That inverted on 2026-09-07 (item 48): it is
            // the ONE field the account half and this half have always agreed on, so leaving it
            // blank hid the only working join behind a field named for the broken one. The two
            // copies are deliberate, and their being equal here is the fact being published.
            new ProfessionalEvent.Subject(null, accountId, accountId),
            data
        );
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
            streamBridge.send(
                binding,
                MessageBuilder.withPayload(envelope).setHeader(KafkaHeaders.KEY, key.getBytes(StandardCharsets.UTF_8)).build()
            );
        } catch (RuntimeException e) {
            log.error("Failed to publish {} for {}", eventType, subject, e);
        }
    }
}
