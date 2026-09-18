package net.jojoaddison.broker;

import java.time.Instant;
import java.util.Map;

/**
 * One document written or removed, on {@code professional.event} — backlog.md item 141, and
 * hc-admin's item 124 for the shape.
 *
 * <h2>Why this is its own record and not {@link ProfessionalEvent}</h2>
 *
 * <p>The two carry the same seven fields and <b>disagree about what {@code subject} is</b>, which is
 * precisely the collision hc-admin's item 124 exists to close. On
 * {@code hc.professional.registration} the subject is <em>a clinician</em>
 * ({@code ProfessionalEvent.Subject(email, accountId)}), and hc-admin's
 * {@code professionalProfileConsumer} reads it that way today. On this channel the subject is
 * <em>the row that changed</em>. One record cannot mean both, and changing the existing one would
 * have altered a topic this item is explicitly not allowed to touch — so the types are separated and
 * {@link EstateEventEnvelope} is what they share. hc-admin reached the same answer independently:
 * their {@code AdminEntityEvent} sits beside their older envelope rather than replacing it.
 *
 * <h2>The estate's answer, decided 2026-09-18</h2>
 *
 * <pre>
 * subject : { entityType, entityId }     the record this event is about
 * data    : { action, actorAccountId }   what happened, and who did it
 * </pre>
 *
 * <p>Four products built this channel in parallel on one day, from prose rather than from a schema,
 * and shipped <b>three</b> shapes under one {@code type} — hc-vendor and hc-admin put the record in
 * {@code subject}, hc-patient put the <em>actor</em> there, and this service left {@code subject}
 * null and flattened all four keys into {@code data}. The architect's decision is the first of those:
 * <em>subject</em> means "the thing this event is about", and an audit row is about the record. The
 * losing readings are worth naming so neither is re-derived from first principles later — <b>subject
 * = the actor</b> is locally consistent with what {@code subject} means on {@code patient-events} and
 * makes the field mean "who" on a stream whose subject is a row; <b>flattening</b>, which is what this
 * service shipped, removes the ambiguity by abandoning a component the shared envelope defines.
 *
 * <p><b>Nobody's code was wrong against its brief.</b> The divergence was a scheduling failure —
 * four parallel briefs describing an envelope in prose — and it is the reason item 124 says the
 * decision is not done until something mechanical fails if a fifth producer diverges again.
 *
 * <h2>The payload rule applies here unchanged, and this is the channel it matters most on</h2>
 *
 * <p>{@link DomainEventEnvelope} states it for this subsystem — <em>identifiers only; never document
 * bytes, names, or contact details</em> — and hc-admin's item 110 arrives at the same payload from
 * the other side, because a channel carrying document contents rebuilds the local mirror their item
 * 107 exists to delete. This is the one wire here that fires for {@code Profile} on the same code
 * path as everything else, so it is the one place a changed value would travel without anybody
 * choosing to send it. {@code EntityChangeEventTest} asserts the key <em>set</em> rather than a list
 * of forbidden names, because a list of things nobody wants cannot fail for a field nobody thought of.
 *
 * @param eventId unique per frame; consumers dedupe on it, delivery being at-least-once.
 * @param type always {@link ProfessionalEventType#ENTITY_CHANGED} — the only type on this channel.
 * @param version {@link #VERSION}.
 * @param occurredAt when the write happened, read on the writing thread and <b>not</b> when the send
 *     ran; those differ by however long the publisher's queue was.
 * @param source always {@code hc-professional-service}.
 * @param subject which record changed. Never null on this channel.
 * @param data {@code action} and, when an account was behind the write, {@code actorAccountId}.
 */
public record EntityChangeEvent(
    String eventId,
    String type,
    int version,
    Instant occurredAt,
    String source,
    Subject subject,
    Map<String, Object> data
)
    implements EstateEventEnvelope {
    /** Matches {@link ProfessionalEvent#VERSION}: one version number across the estate's envelopes. */
    public static final int VERSION = 1;

    /**
     * Which record the event is about.
     *
     * <p><b>This is what changed on 2026-09-18</b>, and it is the whole of hc-admin's item 124 as it
     * applies here: these two keys used to sit in {@code data} with {@code subject} left null.
     *
     * @param entityType the domain class's <b>simple name</b> — {@code Profile}, {@code Team},
     *     {@code PersonalDocument}. Never the Mongo collection name and never the fully-qualified
     *     class, both because the estate's other producers send the simple name and because a package
     *     rename would otherwise silently re-key somebody else's audit trail.
     * @param entityId the document's own id, stringified. Never null on a frame that is published at
     *     all — {@code EntityChangeAnnouncer} drops a change it cannot name, rather than announcing a
     *     row nobody can identify.
     */
    public record Subject(String entityType, String entityId) {}
}
