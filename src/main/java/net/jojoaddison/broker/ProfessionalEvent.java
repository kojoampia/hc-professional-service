package net.jojoaddison.broker;

import java.time.Instant;
import java.util.Map;

/**
 * One thing that happened to one clinician, on {@code hc.professional.registration}.
 *
 * <h2>The estate's shape, and deliberately a copy</h2>
 *
 * <p>Field for field this is hc-patient's {@code PatientEvent} and the identical record in
 * {@code hc-professional/gateway}: {@code eventId}, {@code type}, {@code version},
 * {@code occurredAt}, {@code source}, {@code subject}, {@code data}. Copied rather than shared for
 * the reason hc-patient's own copy gives — three separately deployed, separately versioned
 * applications should not have their release cycles coupled to make a seven-field record less
 * repetitive. What has to agree is the wire shape, and the field names are that agreement.
 *
 * <p>It sits beside {@link DomainEventEnvelope}, which is this stack's original
 * {@code eventType}/{@code actor}/{@code payload} shape and is not being retired: {@code
 * entity.created}, {@code compliance.alert}, {@code message.created} and {@code onboarding.state}
 * all still use it and all have consumers. A reader of either topic dispatches on which of
 * {@code type} and {@code eventType} is present.
 *
 * <h2>The rule about payloads applies here unchanged</h2>
 *
 * <p>{@link DomainEventEnvelope} states it: <em>identifiers only — never document bytes, names, or
 * contact details</em>. This record does not relax it, and {@code ProfileStatus} — identifiers, two
 * booleans and three timestamps — satisfies it outright rather than by exception.
 * {@code Subject.email} exists because the shared shape has the field, and <b>this service leaves it
 * null</b>, exactly as hc-patient leaves {@code patientId} null on the events its gateway publishes.
 *
 * <p><b>And no licence number, in any event, in any version.</b> Not from restraint: this subsystem
 * does not hold one. {@code PersonalDocument} of type {@code LICENSE} carries a name, a checksum, an
 * expiry date and a verification status, and there is no number field anywhere in {@code api/} or
 * {@code web/}. A consumer needing one has to be told that rather than left waiting for a field that
 * is never coming — see backlog.md item 47, and hc-admin's items 33, 35 and 36, whose own text says
 * that answer unblocks them immediately. {@code ProfileStatus} sends {@code isVerified} instead: a
 * fact about a credential rather than the credential.
 */
public record ProfessionalEvent(
    String eventId,
    String type,
    int version,
    Instant occurredAt,
    String source,
    Subject subject,
    Map<String, Object> data
) {
    public static final int VERSION = 1;

    /**
     * Who the event is about.
     *
     * <p>{@code accountId} is the correlation key on this topic and has been since WP3 — the gateway
     * keys {@code registration.created} on it and hc-admin's {@code DirectoryLink.external_key}
     * holds it for every clinician it knows. Moving the correlation onto the email to match
     * hc-patient's key would give one clinician two links.
     *
     * <p><b>The two producers do not fill it with the same thing</b>, and that is a live defect
     * rather than a nuance: the gateway publishes {@code User.id} and this service publishes the
     * login, because the JWT carries no uid claim. See
     * {@link DomainEventPublisher#publishProfileStatus} and backlog.md item 47 § 2b.
     *
     * @param email always null from this service; see the class comment.
     */
    /**
     * Who the event is about, named by <b>one</b> identifier.
     *
     * <p><b>{@code accountId} is the gateway's {@code User.id}, and it is the only join.</b> This
     * record carried a {@code login} beside it until 2026-09-08, and a consumer could correlate on
     * either. That is what made it wrong: two join keys is two answers to "is this the same
     * clinician", and they disagree the moment a login is edited in user management — which orphans
     * every row keyed on the old one. The estate's decision is that the account identifier is the
     * ultimate join and nothing else is.
     *
     * <p>{@code email} stays for the shape hc-patient's {@code PatientEvent.Subject} established and
     * is null from the service half by the identifiers-only rule.
     */
    public record Subject(String email, String accountId) {}
}
