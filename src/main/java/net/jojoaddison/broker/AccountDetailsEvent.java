package net.jojoaddison.broker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * The gateway's {@code AccountDetailsUpdated} frame, as this service reads it — backlog.md row 230,
 * unit A.
 *
 * <h2>A reader record rather than a copy of {@link ProfessionalEvent}, and why</h2>
 *
 * <p>{@code hc.professional.registration} carries <b>two envelope shapes from two producers</b>:
 * {@code registration.created} and {@code onboarding.state} on {@link DomainEventEnvelope}'s
 * {@code eventType}/{@code actor}/{@code payload}, and {@code AccountCreated},
 * {@code AccountActivated}, {@code AccountDetailsUpdated} and {@code ProfileStatus} on
 * {@link ProfessionalEvent}'s {@code type}/{@code subject}/{@code data}. A consumer dispatches on
 * which of {@code type} and {@code eventType} is present — the rule the workspace guide states and
 * both publishers honour.
 *
 * <p>⚠ <b>Dispatching requires reading the frame first, so the bound type has to survive both
 * shapes.</b> Binding {@link ProfessionalEvent} would make every {@code registration.created} a
 * <em>conversion</em> failure before this service's code ran at all — which does not dispatch, it
 * goes to the binder's error path, retries and logs, once per unrelated frame on a topic this
 * service's own publisher writes to. {@link JsonIgnoreProperties} is what stops that, and it is
 * declared <b>here on the record</b> rather than relied on from the global Jackson configuration,
 * because the converter behind a Spring Cloud Stream binding is not necessarily the application's
 * {@code ObjectMapper} and a global default is not a guarantee this file can see.
 *
 * <p>⭐ <b>That tolerance is MEASURED, not reasoned.</b> It was an argument from the annotation until
 * {@code MeterConsumerBindingIT.aForeignEnvelopeOnTheSameTopicDoesNotBlockTheRealOne} sent a real
 * {@code registration.created} frame — {@code eventType}/{@code actor}/{@code payload}, with no
 * {@code type} at all — on the same partition key immediately ahead of a real one, over a real
 * broker. The real frame was still applied and <b>no conversion error was logged</b>: zero
 * occurrences of {@code MessageConversionException}, or of any conversion failure, in the run. So a
 * foreign-shaped frame deserialises here with a {@code null} {@code type} and {@code MeterConsumer}
 * ignores it, which is the estate's "dispatch on whichever of {@code type} and {@code eventType} is
 * present" rule working rather than merely intended.
 *
 * <p><b>So this is deliberately narrower than what is on the wire</b>: the four fields this service
 * acts on and no more. It is not a contract — {@link ProfessionalEvent} in {@code gateway/} is — and
 * it must never grow a field the producer does not send.
 *
 * <p>⛔ <b>{@link #data} carries exactly one key, {@code detailsComplete}, and must not grow.</b> The
 * four fields behind that boolean — {@code firstName}, {@code lastName}, {@code langKey},
 * {@code imageUrl} — are the personal data the estate's identifiers-only rule exists for, and the
 * whole point of a verdict is that nothing on this side ever holds the values. See
 * {@code gateway/ AccountCompleteness}.
 *
 * @param eventId unique per frame; {@code MeterConsumer} dedupes on it, delivery being at-least-once.
 * @param type the discriminator. {@code null} for a {@link DomainEventEnvelope}-shaped frame, which
 *     is how one is recognised and ignored.
 * @param occurredAt when the gateway observed the verdict. ⚠ Load-bearing rather than informational:
 *     it is what stops a reordered redelivery moving step 1 backwards — see
 *     {@code OnboardingService.recordAccountCompleteness}.
 * @param subject who the frame is about. On this topic the subject is <b>a clinician</b>, not a row;
 *     {@link EntityChangeEvent} is the other meaning and the two records are separate for that reason.
 * @param data the verdict.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountDetailsEvent(String eventId, String type, Instant occurredAt, Subject subject, Map<String, Object> data) {
    /** The key {@code data} carries, and the only one this service reads from it. */
    public static final String DETAILS_COMPLETE = "detailsComplete";

    /**
     * The clinician, named by the gateway's {@code User.id} — the estate's sole correlation key for a
     * professional (backlog.md item 50).
     *
     * <p>The producer's {@code Subject} also carries an {@code email}; it is not read here, and
     * {@link JsonIgnoreProperties} is why its absence from this record is not an error. ⛔ Do not add
     * it: correlating on an address is the second join key item 50 exists to remove, and it would put
     * one in this service's logs besides.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subject(String accountId) {}

    /**
     * Whether the gateway says step 1 is satisfied.
     *
     * <p>⚠ <b>A frame whose {@code data} does not carry the key, or carries something that is not a
     * boolean, is not "incomplete" — it is unreadable</b>, and the two must not collapse into
     * {@code false}: that would move a clinician's step 1 backwards on a malformed frame and leave it
     * there. {@code MeterConsumer} refuses such a frame instead.
     */
    public Boolean detailsComplete() {
        Object value = data == null ? null : data.get(DETAILS_COMPLETE);
        return value instanceof Boolean complete ? complete : null;
    }

    /** The account this frame is about, or {@code null} — a frame naming nobody cannot be applied. */
    public String accountId() {
        return subject == null ? null : subject.accountId();
    }
}
