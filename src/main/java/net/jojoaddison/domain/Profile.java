package net.jojoaddison.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A Profile.
 */
@Document(collection = "profile")
public class Profile implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /**
     * The clinician's account, and <b>the correlation key this service publishes to the estate</b>.
     *
     * <p><b>It is the gateway's {@code User.id}</b>, read from the {@code uid} claim by
     * {@code SecurityUtils.getCurrentAccountId()}. It held the gateway <em>login</em> — the JWT
     * subject — from WP1 until backlog.md item 50 on 2026-09-10, and that is why so much of this
     * repository's history is about one field: the gateway keys its own account events on
     * {@code User.id}, so the two producers on {@code hc.professional.registration} named one
     * clinician differently and a consumer joining the account half to the profile half on
     * {@code accountId} matched nothing. hc-admin's {@code SiblingDomainEvent} has always specified
     * this value; the code has only recently agreed with it.
     *
     * <p><b>There is no second identifier and no fallback.</b> A sibling field {@code accountUid}
     * carried the {@code User.id} beside a login-valued {@code accountId} between items 48 and 50,
     * and was deleted with the migration that made them the same value —
     * {@code AccountIdMigrationService} unsets the stored key. A caller whose token carries no
     * {@code uid} resolves to nobody rather than to their login.
     *
     * <p><b>READ_ONLY over HTTP, and that is a security control rather than a modelling preference.</b>
     * This field is the ownership check — {@code findByAccountId} is what decides whose identity
     * documents, roster, absences and patient directory a caller may read
     * ({@code OnboardingDocumentResource}, {@code DutyRosterResource}, {@code AbsenceService},
     * {@code PatientDirectoryService}, {@code RosterTrailService}). It was writable from the
     * request body until 2026-09-08 while {@code PUT /api/profiles/{id}} is a whole-document replace
     * open to all six {@code CLINICAL_MUTATION} roles, so any nurse could point another clinician's
     * profile at their own account in two writes and inherit it. Found by the item 53 review, and a
     * precondition for item 50: a field cannot become the estate's correlation key while any nurse
     * can rewrite it.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Indexed(unique = true, sparse = true)
    @Field("account_id")
    private String accountId;

    /**
     * Push notification preferences (MOB9).
     *
     * <p>They live on the Profile rather than on DeviceToken so they follow the clinician across
     * devices — someone who turns off compliance nudges on their phone means it for their tablet
     * too. Null is treated as opted in for the two delivery flags, so existing profiles keep
     * receiving notifications without a migration.
     *
     * <p>{@code pushShowSenderName} is the exception and defaults to OFF: a lock screen is visible
     * to anyone holding the phone, and even a colleague's name is more than the default should
     * disclose.
     */
    @Field("push_messages_enabled")
    private Boolean pushMessagesEnabled;

    @Field("push_compliance_enabled")
    private Boolean pushComplianceEnabled;

    @Field("push_show_sender_name")
    private Boolean pushShowSenderName;

    @Field("first_name")
    private String firstName;

    @Field("middle_names")
    private String middleNames;

    @Field("last_name")
    private String lastName;

    @Field("birth_date")
    private LocalDate birthDate;

    @Field("sex")
    private String sex;

    @Field("mobile_phone")
    private String mobilePhone;

    @Field("phone_number")
    private String phoneNumber;

    @Field("email")
    private String email;

    @Field("card_type")
    private String cardType;

    @Field("card_number")
    private String cardNumber;

    @Field("address")
    private Address address;

    @Field("title")
    private String title;

    @Field("emergency_contact")
    private EmergencyContact emergencyContact;

    @Field("specialty_category_id")
    private String specialtyCategoryId;

    @Field("team_ids")
    private List<String> teamIds = new ArrayList<>();

    /**
     * When this profile first existed, and when it last changed, and who changed it.
     *
     * <h2>Audited rather than hand-set, deliberately</h2>
     *
     * <p>{@code PersonalDocument} carries the same three as plain {@code @Field}s written by hand at
     * each call site. That is not copied here, because these three are <b>published</b> — hc-admin's
     * professional directory renders {@code createdDate}, {@code modifiedDate} and
     * {@code lastModifiedBy} straight onto its dashboard (backlog.md item 47 § 2b), so a write path
     * that forgot to stamp them would not fail anything here and would show a stale date over there.
     * {@code @EnableMongoAuditing} is already on in {@code DatabaseConfiguration}, so every path that
     * saves a {@code Profile} — {@code ProfileService}, {@code OnboardingService.upsertOwnProfile},
     * the repository directly — stamps them without knowing it has to.
     *
     * <p><b>Null on every profile written before this field existed</b>, and that is left alone
     * rather than backfilled: Mongo has no migration framework here, and inventing a creation date
     * is worse than admitting there is none. The next save of such a profile sets
     * {@code modifiedDate} and leaves {@code createdDate} null, which reads correctly as "changed
     * recently, first seen we do not know when".
     */
    @CreatedDate
    @Field("created_date")
    private Instant createdDate;

    @LastModifiedDate
    @Field("modified_date")
    private Instant modifiedDate;

    /**
     * <b>An account identifier, never a display name.</b> {@code SpringSecurityAuditorAware} fills it
     * from the JWT subject, which is what this service calls an {@code accountId} everywhere else —
     * the same identifier space as {@link #accountId}. See the warning on that field.
     */
    @LastModifiedBy
    @Field("last_modified_by")
    private String lastModifiedBy;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public Instant getCreatedDate() {
        return this.createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getModifiedDate() {
        return this.modifiedDate;
    }

    public void setModifiedDate(Instant modifiedDate) {
        this.modifiedDate = modifiedDate;
    }

    public String getLastModifiedBy() {
        return this.lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public String getId() {
        return this.id;
    }

    public Profile id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccountId() {
        return this.accountId;
    }

    public Profile accountId(String accountId) {
        this.setAccountId(accountId);
        return this;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getFirstName() {
        return this.firstName;
    }

    public Profile firstName(String firstName) {
        this.setFirstName(firstName);
        return this;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getMiddleNames() {
        return this.middleNames;
    }

    public Profile middleNames(String middleNames) {
        this.setMiddleNames(middleNames);
        return this;
    }

    public void setMiddleNames(String middleNames) {
        this.middleNames = middleNames;
    }

    public String getLastName() {
        return this.lastName;
    }

    public Profile lastName(String lastName) {
        this.setLastName(lastName);
        return this;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public LocalDate getBirthDate() {
        return this.birthDate;
    }

    public Profile birthDate(LocalDate birthDate) {
        this.setBirthDate(birthDate);
        return this;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public String getSex() {
        return this.sex;
    }

    public Profile sex(String sex) {
        this.setSex(sex);
        return this;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public String getMobilePhone() {
        return this.mobilePhone;
    }

    public Profile mobilePhone(String mobilePhone) {
        this.setMobilePhone(mobilePhone);
        return this;
    }

    public void setMobilePhone(String mobilePhone) {
        this.mobilePhone = mobilePhone;
    }

    public String getPhoneNumber() {
        return this.phoneNumber;
    }

    public Profile phoneNumber(String phoneNumber) {
        this.setPhoneNumber(phoneNumber);
        return this;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getEmail() {
        return this.email;
    }

    public Profile email(String email) {
        this.setEmail(email);
        return this;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCardType() {
        return this.cardType;
    }

    public Profile cardType(String cardType) {
        this.setCardType(cardType);
        return this;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    public String getCardNumber() {
        return this.cardNumber;
    }

    public Profile cardNumber(String cardNumber) {
        this.setCardNumber(cardNumber);
        return this;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public Address getAddress() {
        return this.address;
    }

    public Profile address(Address address) {
        this.setAddress(address);
        return this;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public String getTitle() {
        return this.title;
    }

    public Profile title(String title) {
        this.setTitle(title);
        return this;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public EmergencyContact getEmergencyContact() {
        return this.emergencyContact;
    }

    public Profile emergencyContact(EmergencyContact emergencyContact) {
        this.setEmergencyContact(emergencyContact);
        return this;
    }

    public void setEmergencyContact(EmergencyContact emergencyContact) {
        this.emergencyContact = emergencyContact;
    }

    public String getSpecialtyCategoryId() {
        return this.specialtyCategoryId;
    }

    public Profile specialtyCategoryId(String specialtyCategoryId) {
        this.setSpecialtyCategoryId(specialtyCategoryId);
        return this;
    }

    public void setSpecialtyCategoryId(String specialtyCategoryId) {
        this.specialtyCategoryId = specialtyCategoryId;
    }

    public List<String> getTeamIds() {
        return this.teamIds;
    }

    public Profile teamIds(List<String> teamIds) {
        this.setTeamIds(teamIds);
        return this;
    }

    public void setTeamIds(List<String> teamIds) {
        this.teamIds = teamIds;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and
    // setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Profile)) {
            return false;
        }
        return getId() != null && getId().equals(((Profile) o).getId());
    }

    @Override
    public int hashCode() {
        // see
        // https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "Profile{" +
                "id=" + getId() +
                ", firstName='" + getFirstName() + "'" +
                ", middleNames='" + getMiddleNames() + "'" +
                ", lastName='" + getLastName() + "'" +
                ", birthDate='" + getBirthDate() + "'" +
                ", sex='" + getSex() + "'" +
                ", mobilePhone='" + getMobilePhone() + "'" +
                ", phoneNumber='" + getPhoneNumber() + "'" +
                ", email='" + getEmail() + "'" +
                ", cardType='" + getCardType() + "'" +
                ", cardNumber='" + getCardNumber() + "'" +
                ", address='" + getAddress() + "'" +
                ", title='" + getTitle() + "'" +
                ", emergencyContact='" + getEmergencyContact() + "'" +
                ", specialtyCategoryId='" + getSpecialtyCategoryId() + "'" +
                ", teamIds='" + getTeamIds() + "'" +
                "}";
    }

    public Boolean getPushMessagesEnabled() {
        return pushMessagesEnabled;
    }

    public void setPushMessagesEnabled(Boolean pushMessagesEnabled) {
        this.pushMessagesEnabled = pushMessagesEnabled;
    }

    public Profile pushMessagesEnabled(Boolean pushMessagesEnabled) {
        this.setPushMessagesEnabled(pushMessagesEnabled);
        return this;
    }

    public Boolean getPushComplianceEnabled() {
        return pushComplianceEnabled;
    }

    public void setPushComplianceEnabled(Boolean pushComplianceEnabled) {
        this.pushComplianceEnabled = pushComplianceEnabled;
    }

    public Profile pushComplianceEnabled(Boolean pushComplianceEnabled) {
        this.setPushComplianceEnabled(pushComplianceEnabled);
        return this;
    }

    public Boolean getPushShowSenderName() {
        return pushShowSenderName;
    }

    public void setPushShowSenderName(Boolean pushShowSenderName) {
        this.pushShowSenderName = pushShowSenderName;
    }

    public Profile pushShowSenderName(Boolean pushShowSenderName) {
        this.setPushShowSenderName(pushShowSenderName);
        return this;
    }
}
