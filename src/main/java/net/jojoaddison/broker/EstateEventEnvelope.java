package net.jojoaddison.broker;

/**
 * The seven-field shape the estate's per-product channels share:
 * {@code {eventId, type, version, occurredAt, source, subject, data}}.
 *
 * <p><b>It is a marker, and its only job is to stop an arbitrary payload reaching a binding.</b>
 * {@link DomainEventPublisher#publishShared} is typed to this rather than to {@code Object} for the
 * reason that method already gave about its older twin: widening the parameter would remove the one
 * thing standing between these destinations and any object a caller happened to have. Sealed, so the
 * set of things that can go on an estate channel from this service is a list the compiler keeps.
 *
 * <h2>Two envelopes, because {@code subject} means two different things</h2>
 *
 * <p>The two permitted records are <b>not</b> interchangeable and the difference is the whole reason
 * this interface exists rather than one record serving both:
 *
 * <ul>
 *   <li>{@link ProfessionalEvent} — {@code subject} is {@code (email, accountId)}, <b>a clinician</b>.
 *       It rides {@code hc.professional.registration} and is the shape hc-admin's
 *       {@code professionalProfileConsumer} already reads.</li>
 *   <li>{@link EntityChangeEvent} — {@code subject} is {@code (entityType, entityId)}, <b>a
 *       document</b>. It rides {@code professional.event} and nothing else.</li>
 * </ul>
 *
 * <p>⚠ <b>Do not merge them.</b> hc-admin's item 124 is the record of four products shipping three
 * different meanings of {@code subject} under one {@code type}, and the fix for it was to make
 * {@code subject} name the record that changed. Applying that to {@link ProfessionalEvent} would
 * break the live registration topic, which needs {@code subject} to go on naming a person; reusing
 * {@link ProfessionalEvent} for entity changes would put a document's identifiers into fields called
 * {@code email} and {@code accountId}. Separating the types is what lets one channel change without
 * the other, and it is the same answer hc-admin reached — their {@code AdminEntityEvent} sits beside
 * their existing envelope rather than replacing it.
 */
public sealed interface EstateEventEnvelope permits ProfessionalEvent, EntityChangeEvent {}
