package net.jojoaddison.broker;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
 */
@Component
public class DomainEventPublisher {

    public static final String ENTITY_TOPIC_BINDING = "entityEvents-out-0";
    private static final String SOURCE = "hc-professional-service";

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final StreamBridge streamBridge;

    public DomainEventPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
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
        try {
            streamBridge.send(
                ENTITY_TOPIC_BINDING,
                MessageBuilder.withPayload(envelope).setHeader(KafkaHeaders.KEY, entityId.getBytes()).build()
            );
        } catch (RuntimeException e) {
            log.error("Failed to publish entity.created for {} {}", entityType, entityId, e);
        }
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
        try {
            streamBridge.send(
                ENTITY_TOPIC_BINDING,
                MessageBuilder.withPayload(envelope).setHeader(KafkaHeaders.KEY, entityId.getBytes()).build()
            );
        } catch (RuntimeException e) {
            log.error("Failed to publish compliance.alert for {} {}", alertType, entityId, e);
        }
    }
}
