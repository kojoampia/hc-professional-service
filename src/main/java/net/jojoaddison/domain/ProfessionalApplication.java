package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A professional onboarding application (onboarding workflow § Data
 * contracts). One application per account (unique {@code accountId}); every
 * status change must be accompanied by an appended {@link OnboardingEvent}.
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

    @Field("profile_id")
    private String profileId;

    /** One of the nine clinical authorities, e.g. ROLE_NURSE. */
    @Field("requested_role")
    private String requestedRole;

    @Field("status")
    private OnboardingStatus status;

    @Field("consent_accepted_at")
    private Instant consentAcceptedAt;

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

    public String getRequestedRole() {
        return this.requestedRole;
    }

    public ProfessionalApplication requestedRole(String requestedRole) {
        this.requestedRole = requestedRole;
        return this;
    }

    public void setRequestedRole(String requestedRole) {
        this.requestedRole = requestedRole;
    }

    public OnboardingStatus getStatus() {
        return this.status;
    }

    public ProfessionalApplication status(OnboardingStatus status) {
        this.status = status;
        return this;
    }

    public void setStatus(OnboardingStatus status) {
        this.status = status;
    }

    public Instant getConsentAcceptedAt() {
        return this.consentAcceptedAt;
    }

    public ProfessionalApplication consentAcceptedAt(Instant consentAcceptedAt) {
        this.consentAcceptedAt = consentAcceptedAt;
        return this;
    }

    public void setConsentAcceptedAt(Instant consentAcceptedAt) {
        this.consentAcceptedAt = consentAcceptedAt;
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
                ", requestedRole='" + getRequestedRole() + "'" +
                ", status='" + getStatus() + "'" +
                ", submittedAt='" + getSubmittedAt() + "'" +
                ", decidedBy='" + getDecidedBy() + "'" +
                ", decidedAt='" + getDecidedAt() + "'" +
                "}";
    }
}
