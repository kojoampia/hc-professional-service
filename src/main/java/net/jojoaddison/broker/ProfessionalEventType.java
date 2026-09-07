package net.jojoaddison.broker;

/**
 * The estate-shaped types on {@code hc.professional.registration}, and which application sends
 * which.
 *
 * <p>The three of them are one clinician's arrival told in two halves. {@link #ACCOUNT_CREATED} and
 * {@link #ACCOUNT_ACTIVATED} are the account — published by the gateway, which owns users and
 * authentication, and carrying nothing clinical because at those moments nothing clinical exists.
 * {@link #PROFILE_UPDATED} is the second half, published here, because this service owns the domain
 * and the profile.
 *
 * <p><b>Why two halves rather than one richer event.</b> A registration cannot say anything about a
 * clinician's profile, because there is no profile at that moment — the account holds
 * {@code ROLE_USER} alone and the wizard has not run. A consumer therefore has to wait for the
 * second half whatever shape the first takes, so the first is kept honest rather than padded with
 * fields it would have to invent. <b>The two are joined on {@code accountId}</b>: the account half
 * carries the name a directory displays, and this half carries the state it displays beside it.
 *
 * <p><b>Neither half carries a clinical role or a licence number, and that is settled rather than
 * pending.</b> The estate's rule is identifiers only, and a licence number is a credential rather
 * than an identifier — but the question does not even arise here, because this subsystem holds no
 * licence number at all: {@code PersonalDocument} of type {@code LICENSE} has a name, a checksum, an
 * expiry date and a verification status and no number field, in {@code api/} or in {@code web/}.
 * {@link #PROFILE_STATUS} sends {@code isVerified} instead — a fact <em>about</em> a credential,
 * which is what a directory is actually asking.
 *
 * <p>A consumer meeting a type it does not recognise on this topic must ignore it, which is what
 * makes any of these additive rather than breaking. The older
 * {@code registration.created} / {@code onboarding.state} pair, on {@link DomainEventEnvelope}'s
 * shape, continues alongside them.
 */
public final class ProfessionalEventType {

    /** Published by {@code hc-professional-gateway}; named here because this stack's contract is one document. */
    public static final String ACCOUNT_CREATED = "AccountCreated";

    /** Published by {@code hc-professional-gateway} when the activation link is followed. */
    public static final String ACCOUNT_ACTIVATED = "AccountActivated";

    /**
     * Published by this service whenever a clinician's profile, or its completeness or verification
     * state, changes.
     *
     * <p>Idempotent by construction: a snapshot of what a directory needs, not a delta, so a
     * redelivery or a full replay applies the same values twice.
     *
     * <p><b>Not an enrichment of {@code entity.created}, and not on the entity topic</b>, for three
     * reasons. That event fires for ten entity types, so a Profile-shaped payload on it would be
     * wrong for nine of them; it fires only on <em>create</em>, while this has to fire on every
     * change or the record it feeds can never be refreshed; and hc-admin is not subscribed to
     * {@code hc.professional.entity} at all, so an event published there would be heard by nobody.
     */
    public static final String PROFILE_STATUS = "ProfileStatus";

    private ProfessionalEventType() {}
}
