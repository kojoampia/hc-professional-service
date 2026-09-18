package net.jojoaddison.broker;

/**
 * The estate-shaped types this subsystem puts on the wire, and which application sends which.
 *
 * <p><b>Two channels, and they divide by subject rather than by shape.</b> The first three names
 * below ride {@code hc.professional.registration} and are each about <em>a clinician</em>;
 * {@link #ENTITY_CHANGED} rides {@code professional.event} and is about <em>a document</em>. They
 * share the {@link ProfessionalEvent} envelope because every reader in the estate dispatches on
 * {@code type}, so one shape costs a consumer nothing while a second shape would cost it a parser.
 *
 * <p>The first three are one clinician's arrival told in two halves. {@link #ACCOUNT_CREATED} and
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

    /**
     * Published by this service on {@code professional.event} for <b>every</b> document written or
     * removed, in every collection — backlog.md item 141, and hc-admin's item 110 for the estate-wide
     * decision that produced it.
     *
     * <p><b>The only {@code type} on that channel</b>, deliberately. Which of created, updated or
     * deleted it was is {@link EntityChangeAction} in {@code data.action}, for the reason that enum
     * and {@link DomainEventPublisher#publishOnboardingState} both give: a consumer should switch on
     * one field rather than match a family of event names that grows with every collection.
     *
     * <p><b>The literal is {@code "EntityChanged"} — past tense — and it is the estate's, not this
     * service's.</b> All four products' {@code .event} channels carry this one value, decided by the
     * architect on 2026-09-18 (hc-admin item 124). This service published {@code "EntityChange"}
     * until then and hc-vendor published a <em>family</em> of three, {@code EntityCreated} /
     * {@code EntityUpdated} / {@code EntityDeleted}; item 124's own text had asserted that all four
     * already agreed, which was wrong about both. The family reading is idiomatic and lets a consumer
     * subscribe to one kind, and it lost because it costs three products a change rather than two and
     * makes a consumer match a set of literals instead of one.
     *
     * <p>⚠ <b>The constant's name tracks the value, and that is why it is {@code ENTITY_CHANGED}.</b>
     * Every other constant in this class is the value in screaming snake — {@code ACCOUNT_CREATED} is
     * {@code "AccountCreated"} — and a constant reading {@code ENTITY_CHANGE} while holding
     * {@code "EntityChanged"} is a stale reference frozen into the declaration, which is the rename
     * hazard this estate keeps finding. Note that the <em>concept</em> keeps the present tense
     * throughout — {@link EntityChangeEvent}, {@link EntityChangeAction},
     * {@code EntityChangeAnnouncer}, {@code publishEntityChange} — because those name an entity
     * change, not this wire literal. Only the type constant follows the wire.
     *
     * <p><b>Not an enrichment of {@link #PROFILE_STATUS} and not a replacement for it.</b> That frame
     * is a <em>snapshot of a clinician's published state</em> for a directory to render; this one is
     * a <em>record that a row changed</em> for an audit trail to append. The first is idempotent and
     * lossy on purpose — replaying it twice yields the same directory — while the second is neither,
     * because "it changed twice" is the fact it exists to carry. Nothing about one can be derived
     * from the other, which is why both channels stand.
     *
     * <p><b>The payload is identifiers and metadata only — never the changed values.</b> That rule
     * has two independent origins and both apply here: {@link DomainEventEnvelope} states it for this
     * subsystem, and hc-admin's item 110 reaches the same payload from the other direction, because a
     * channel carrying document contents rebuilds the local mirror their item 107 exists to delete.
     */
    public static final String ENTITY_CHANGED = "EntityChanged";

    private ProfessionalEventType() {}
}
