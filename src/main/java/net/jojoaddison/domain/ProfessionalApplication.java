package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A professional onboarding application (onboarding workflow § Data
 * contracts). One application per account (unique {@code accountId}); every
 * status change must be accompanied by an appended {@link OnboardingEvent}.
 *
 * <h2>{@code profile.md} step 4 is the model for the four fields below</h2>
 *
 * <p>The specification gives step 4's model as {@code agreed}, {@code profileId}, {@code authority}
 * and {@code agreedDate}, and instructs <i>"not exhaustive; {@code ProfessionalApplication.java}
 * has more. <b>Synchronize this.</b>"</i> — so the table is reconciled against this class rather
 * than only added to. What that produced:
 *
 * <table>
 *   <caption>The step-4 fields and what each replaced</caption>
 *   <tr><th>field</th><th>state before T3</th></tr>
 *   <tr><td>{@link #agreed}</td><td><b>new.</b> Consent was recorded only as a timestamp</td></tr>
 *   <tr><td>{@link #agreedDate}</td><td><b>renamed</b> from {@code consentAcceptedAt}, values
 *       carried across by {@code ProfessionalApplicationConsentMigration}</td></tr>
 *   <tr><td>{@link #authority}</td><td><b>renamed</b> from {@code requestedRole}, same migration</td></tr>
 *   <tr><td>{@link #profileId}</td><td>already here; now also set on creation, from
 *       {@code Profile.id}</td></tr>
 * </table>
 *
 * <p>⛔ <b>{@code consentAcceptedAt} is gone rather than kept beside {@code agreedDate}.</b> One
 * field per fact: two timestamps for one consent is the shape that lets a reader, a migration and a
 * progress predicate each pick a different one. The owner's decision is to migrate the values and
 * remove the old key, and <i>"a field that is always true needn't be stored"</i> was overruled for
 * {@link #agreed} in the same breath — consent is mandatory to create an application, and the
 * boolean is stored anyway, because {@code profile.md}'s § "Consent statement" renders
 * {@code Application.agreed} directly and a view should read the fact it displays rather than
 * inferring it from the presence of a date.
 */
@Document(collection = "professional_application")
public class ProfessionalApplication extends AbstractAuditingEntity<String> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** Gateway User.id — canonical account linkage. */
    @Indexed(unique = true, sparse = true)
    @Field("account_id")
    private String accountId;

    /** Gateway login, denormalized for display/search only. */
    @Field("login")
    private String login;

    /**
     * The applicant's {@code Profile.id} (profile.md step 4: <i>"Set to {@code Profile.id}"</i>).
     *
     * <p>Written at creation from the caller's own profile and again by
     * {@code OnboardingService.completeProfile}. It can still be null — an application created
     * before the profile exists has nothing to point at — and every reader of it already treats
     * null as "no linked profile" with a 409.
     */
    @Field("profile_id")
    private String profileId;

    /**
     * The clinical role being applied for — one of the eight professional authorities, e.g.
     * {@code ROLE_NURSE} (profile.md step 4: <i>"Role requested"</i>).
     *
     * <p><b>A {@code String}, and it stays one.</b> {@code profile.md} § Gap Update:
     * <i>"{@code Authority} is a class defined in the gateway. The {@code api} service holds only
     * the role string."</i> This was {@code requestedRole} until T3.
     */
    @Field("authority")
    private String authority;

    @Field("status")
    private ProfileStatus status;

    /**
     * Whether consent was ticked (profile.md step 4: <i>"consent ticked"</i>).
     *
     * <p>A primitive rather than a {@code Boolean}: after
     * {@code ProfessionalApplicationConsentMigration} every stored row carries the key, and a
     * missing key maps to {@code false}, which is the right answer for a row that recorded no
     * consent at all. There is deliberately no third "unknown" state to render.
     */
    @Field("agreed")
    private boolean agreed;

    /**
     * When consent was given — <b>stamped by the server</b> (profile.md step 4: <i>"Server
     * stamp"</i>), never taken from a request body.
     */
    @Field("agreed_date")
    private Instant agreedDate;

    /** Login of the inviting administrator; null for self-service. */
    @Field("invited_by")
    private String invitedBy;

    @Field("submitted_at")
    private Instant submittedAt;

    @Field("decided_by")
    private String decidedBy;

    @Field("decided_at")
    private Instant decidedAt;

    @Field("decision_reason")
    private String decisionReason;

    @Field("correction_notes")
    private String correctionNotes;

    /** Acquisition attribution, e.g. "web-careers" (careers handoff contract §3). */
    @Field("source")
    private String source;

    @Override
    public String getId() {
        return this.id;
    }

    public ProfessionalApplication id(String id) {
        this.id = id;
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccountId() {
        return this.accountId;
    }

    public ProfessionalApplication accountId(String accountId) {
        this.accountId = accountId;
        return this;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getLogin() {
        return this.login;
    }

    public ProfessionalApplication login(String login) {
        this.login = login;
        return this;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getProfileId() {
        return this.profileId;
    }

    public ProfessionalApplication profileId(String profileId) {
        this.profileId = profileId;
        return this;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getAuthority() {
        return this.authority;
    }

    public ProfessionalApplication authority(String authority) {
        this.authority = authority;
        return this;
    }

    public void setAuthority(String authority) {
        this.authority = authority;
    }

    public ProfileStatus getStatus() {
        return this.status;
    }

    public ProfessionalApplication status(ProfileStatus status) {
        this.status = status;
        return this;
    }

    public void setStatus(ProfileStatus status) {
        this.status = status;
    }

    public boolean isAgreed() {
        return this.agreed;
    }

    public ProfessionalApplication agreed(boolean agreed) {
        this.agreed = agreed;
        return this;
    }

    public void setAgreed(boolean agreed) {
        this.agreed = agreed;
    }

    public Instant getAgreedDate() {
        return this.agreedDate;
    }

    public ProfessionalApplication agreedDate(Instant agreedDate) {
        this.agreedDate = agreedDate;
        return this;
    }

    public void setAgreedDate(Instant agreedDate) {
        this.agreedDate = agreedDate;
    }

    public String getInvitedBy() {
        return this.invitedBy;
    }

    public ProfessionalApplication invitedBy(String invitedBy) {
        this.invitedBy = invitedBy;
        return this;
    }

    public void setInvitedBy(String invitedBy) {
        this.invitedBy = invitedBy;
    }

    public Instant getSubmittedAt() {
        return this.submittedAt;
    }

    public ProfessionalApplication submittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
        return this;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getDecidedBy() {
        return this.decidedBy;
    }

    public ProfessionalApplication decidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
        return this;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return this.decidedAt;
    }

    public ProfessionalApplication decidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
        return this;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionReason() {
        return this.decisionReason;
    }

    public ProfessionalApplication decisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
        return this;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public String getSource() {
        return this.source;
    }

    public ProfessionalApplication source(String source) {
        this.source = source;
        return this;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCorrectionNotes() {
        return this.correctionNotes;
    }

    public ProfessionalApplication correctionNotes(String correctionNotes) {
        this.correctionNotes = correctionNotes;
        return this;
    }

    public void setCorrectionNotes(String correctionNotes) {
        this.correctionNotes = correctionNotes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfessionalApplication)) {
            return false;
        }
        return getId() != null && getId().equals(((ProfessionalApplication) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "ProfessionalApplication{" +
                "id=" + getId() +
                ", accountId='" + getAccountId() + "'" +
                ", login='" + getLogin() + "'" +
                ", profileId='" + getProfileId() + "'" +
                ", authority='" + getAuthority() + "'" +
                ", status='" + getStatus() + "'" +
                ", agreed='" + isAgreed() + "'" +
                ", agreedDate='" + getAgreedDate() + "'" +
                ", submittedAt='" + getSubmittedAt() + "'" +
                ", decidedBy='" + getDecidedBy() + "'" +
                ", decidedAt='" + getDecidedAt() + "'" +
                ", source='" + getSource() + "'" +
                "}";
    }
}
