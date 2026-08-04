package net.jojoaddison.broker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.jojoaddison.config.WebsocketConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code message.created} event into a websocket nudge for the recipient.
 *
 * <p>This is the middle of the flow: the service writes the message and publishes identifiers, this
 * consumer pushes those identifiers to whoever is connected as that account, and the client then
 * fetches the message over HTTP. Nothing here reads or forwards message content, so the socket
 * cannot leak what the read endpoint would have refused.
 *
 * <p><b>Dedupe on eventId.</b> Delivery is at-least-once, so the same event can arrive more than
 * once; without this a redelivery would show the recipient a second notification for one message.
 * The seen-set is bounded and in-memory, which is the right shape for a de-duplication window over
 * a live socket — it is not a durability mechanism, and a restart legitimately forgets it.
 *
 * <p>Events for other types on the same topic ({@code entity.created}, {@code compliance.alert})
 * are ignored rather than logged as errors: the topic is shared by design.
 */
@Component("messageEvents")
public class MessageEventConsumer implements Consumer<DomainEventEnvelope> {

    private static final Logger log = LoggerFactory.getLogger(MessageEventConsumer.class);
    private static final String MESSAGE_CREATED = "message.created";
    private static final int SEEN_CAPACITY = 5000;

    /** Bounded LRU of eventIds already delivered. Access is synchronised; volume here is tiny. */
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

    private final SimpMessagingTemplate messagingTemplate;

    public MessageEventConsumer(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void accept(DomainEventEnvelope envelope) {
        if (envelope == null || !MESSAGE_CREATED.equals(envelope.eventType())) {
            return;
        }
        if (envelope.eventId() != null && !seen.add(envelope.eventId())) {
            log.debug("Ignoring redelivered {}", envelope.eventId());
            return;
        }
        Map<String, Object> payload = envelope.payload();
        if (payload == null) {
            return;
        }
        Object recipientId = payload.get("recipientId");
        if (recipientId == null) {
            log.warn("message.created {} has no recipientId — cannot route", envelope.eventId());
            return;
        }
        // Identifiers only, mirroring the Kafka payload. The client fetches the message itself from
        // GET /api/messaging/messages/{messageId}.
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("messageId", payload.get("messageId"));
        notification.put("conversationId", payload.get("conversationId"));
        notification.put("occurredAt", envelope.occurredAt());

        // The destination resolves against the STOMP principal, which is the gateway login — the
        // same value recipientId holds. See WebsocketConfiguration for why that equality matters.
        messagingTemplate.convertAndSendToUser(recipientId.toString(), WebsocketConfiguration.USER_DESTINATION, notification);
        log.debug("Notified {} of message {}", recipientId, payload.get("messageId"));
    }
}
