package net.jojoaddison.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jojoaddison.broker.DomainEventEnvelope;
import net.jojoaddison.service.PushNotificationService.PushPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns domain events into push notifications.
 *
 * <p>Follows {@code MessageEventConsumer} deliberately closely, including the bounded LRU dedupe on
 * {@code eventId} and the convention of ignoring event types it does not handle rather than logging
 * them as errors — the topic is shared by design.
 *
 * <p><b>Why this lives in {@code service} and not {@code broker}.</b> ArchUnit's
 * {@code TechnicalStructureTest} allows the Service layer to be reached only from Web and Config;
 * {@code broker} is not a declared layer, so a consumer there could not call ProfileService or
 * PushNotificationService. Rather than weaken the rule, the handler sits in the layer it belongs to
 * and {@code PushNotificationConfiguration} registers the Spring Cloud Function binding — Config may
 * reach Service, and nothing reaches Config.
 *
 * <p><b>The consumer group must differ from the websocket consumer's.</b> This binds to
 * {@code hc-professional-ms-push} while {@code messageEvents-in-0} binds to
 * {@code hc-professional-ms-messaging}. Same group would make the two compete for partitions, so
 * each would see roughly half the events — producing "notifications work about half the time",
 * which is a miserable bug to chase because nothing errors.
 *
 * <p><b>Both transports always fire.</b> This does not try to work out whether the recipient has a
 * live socket: the server cannot know reliably, and guessing produces missed notifications. The
 * client dedupes on messageId across push and STOMP.
 *
 * <p><b>It names copy, it does not write copy (MOB10).</b> What goes out is a bundle key and, at
 * most, the sender's name as an argument; the text is rendered per <em>device</em> from
 * {@code DeviceToken.langKey} by {@link PushCopyService}, because one account's handsets can be set
 * to different languages. This handler knows the recipient's account and nothing about their phones.
 */
@Service
public class PushEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PushEventHandler.class);

    private static final String MESSAGE_CREATED = "message.created";
    private static final String COMPLIANCE_ALERT = "compliance.alert";
    private static final int SEEN_CAPACITY = 5000;

    /** Bounded LRU of eventIds already pushed. In-memory by design; a restart legitimately forgets. */
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

    private final PushNotificationService pushNotificationService;
    private final ProfileService profileService;

    public PushEventHandler(PushNotificationService pushNotificationService, ProfileService profileService) {
        this.pushNotificationService = pushNotificationService;
        this.profileService = profileService;
    }

    /** Handles one event. Never throws — a notification failure must not poison the consumer. */
    public void handle(DomainEventEnvelope envelope) {
        if (envelope == null || envelope.payload() == null) {
            return;
        }
        String type = envelope.eventType();
        if (!MESSAGE_CREATED.equals(type) && !COMPLIANCE_ALERT.equals(type)) {
            return;
        }
        if (envelope.eventId() != null && !seen.add(envelope.eventId())) {
            log.debug("Ignoring redelivered {}", envelope.eventId());
            return;
        }

        try {
            if (MESSAGE_CREATED.equals(type)) {
                onMessageCreated(envelope);
            } else {
                onComplianceAlert(envelope);
            }
        } catch (Exception e) {
            // A notification failure must never poison the consumer or fail the write that
            // produced the event.
            log.error("Could not push for event {}", envelope.eventId(), e);
        }
    }

    private void onMessageCreated(DomainEventEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        Object recipientId = payload.get("recipientId");
        if (recipientId == null) {
            log.warn("message.created {} has no recipientId — cannot route", envelope.eventId());
            return;
        }
        String accountId = recipientId.toString();
        if (!profileService.wantsMessagePush(accountId)) {
            return;
        }

        // Default body reveals nothing. The sender's name appears only for a recipient who has
        // opted in — a lock screen is visible to anyone holding the phone, and even a colleague's
        // name is more than the default should disclose.
        boolean showSender = profileService.wantsSenderNameInPush(accountId);
        String sender = showSender ? String.valueOf(payload.getOrDefault("senderName", "")) : "";

        boolean named = showSender && !sender.isBlank();

        pushNotificationService.sendToAccount(
            accountId,
            new PushPayload(
                "push.message.title",
                named ? "push.message.body.named" : "push.message.body",
                // The sender's name is the only argument any push copy takes, and it travels as an
                // argument rather than a composed string so the word order can differ per language.
                named ? List.of(sender) : List.of(),
                // Collapse per conversation so a busy thread is one tray row, not ten.
                String.valueOf(payload.getOrDefault("conversationId", "messages")),
                Map.of(
                    "type",
                    MESSAGE_CREATED,
                    "messageId",
                    String.valueOf(payload.getOrDefault("messageId", "")),
                    "conversationId",
                    String.valueOf(payload.getOrDefault("conversationId", "")),
                    "occurredAt",
                    String.valueOf(envelope.occurredAt())
                )
            )
        );
    }

    private void onComplianceAlert(DomainEventEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        Object accountId = payload.get("accountId");
        if (accountId == null) {
            // Admin-scoped sweeps have no single owner to notify. Not an error.
            log.debug("compliance.alert {} has no accountId — nothing to notify", envelope.eventId());
            return;
        }
        String account = accountId.toString();
        if (!profileService.wantsCompliancePush(account)) {
            return;
        }

        pushNotificationService.sendToAccount(
            account,
            new PushPayload(
                "push.compliance.title",
                "push.compliance.body",
                List.of(),
                "compliance",
                Map.of(
                    "type",
                    COMPLIANCE_ALERT,
                    "alertType",
                    String.valueOf(payload.getOrDefault("alertType", "")),
                    "entityId",
                    String.valueOf(payload.getOrDefault("entityId", "")),
                    "occurredAt",
                    String.valueOf(envelope.occurredAt())
                )
            )
        );
    }
}
