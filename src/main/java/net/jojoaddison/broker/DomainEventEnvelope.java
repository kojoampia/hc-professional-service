package net.jojoaddison.broker;

import java.time.Instant;
import java.util.Map;

/**
 * JSON envelope for admin-portal domain events
 * (professional-onboarding-workflow.md § Domain events). Payloads carry
 * identifiers only — never document bytes, names, or contact details.
 */
public record DomainEventEnvelope(
    String eventId,
    String eventType,
    Instant occurredAt,
    String source,
    String actor,
    Map<String, Object> payload
) {}
