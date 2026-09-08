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
     * <p><b>It is the gateway <em>login</em>, not the gateway's {@code User.id}, and that matters
     * outside this repository.</b> It is set from {@code OnboardingResource.currentAccountId()},
     * which returns {@code SecurityUtils.getCurrentUserLogin()} — the JWT subject, which the gateway
     * fills with {@code authentication.getName()}. The gateway meanwhile keys
     * {@code registration.created} and its account events on {@code User.id}. So the two producers
     * on {@code hc.professional.registration} have never named one clinician the same way under
     * that name, and a consumer joining the account half to the profile half on {@code accountId}
     * matches nothing.
     *
     * <p><b>Two things changed on 2026-09-07 (backlog.md item 48) and neither is this field.</b> The
     * gateway's token now carries a {@code uid} claim, and {@link #accountUid} beside this holds it
     * for publication; and {@code ProfileStatus} now fills {@code subject.login}, which is the field
     * the two halves have always agreed on and is therefore the join that works. <b>This field
     * remains the login and remains the only identity this service resolves a caller by</b> — see
     * {@link #accountUid} for why rewriting it was rejected rather than deferred.
     *
     * <p><b>READ_ONLY over HTTP, and that is a security control rather than a modelling preference.</b>
     * This field is the ownership check — {@code findByAccountId(login)} is what decides whose identity
     * documents, roster, absences and patient directory a caller may read
     * ({@code OnboardingDocumentResource:173}, {@code DutyRosterResource:388}, {@code AbsenceService:287},
     * {@code PatientDirectoryService:109}, {@code RosterTrailService:139}). It was writable from the
     * request body until 2026-09-08 while {@code PUT /api/profiles/{id}} is a whole-document replace
     * open to all six {@code CLINICAL_MUTATION} roles, so any nurse could point another clinician's
     * profile at their own login in two writes and inherit it. Found by the item 53 review.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Indexed(unique = true, sparse = true)
    @Field("account_id")
    private String accountId;

    /**
     * The gateway's {@code User.id} for the same account, when the clinician has signed in since the
     * token started carrying it — <b>the identifier the estate's account half is keyed by</b>.
     *
     * <p>Added on 2026-09-07 (backlog.md item 48) beside {@link #accountId} rather than instead of
     * it. The alternative was to migrate: rewrite every stored login in this database to a
     * {@code User.id}. That was rejected and the reasons are worth keeping, because they do not
     * expire. The mapping lives in the gateway's {@code hcProfessionalGateway} database, which this
     * service cannot read. It is not total — {@code "system"}, an account since deleted, and a login
     * arriving on a token minted by hc-admin or hc-patient (the three share a signing key and
     * {@code TokenOriginValidator} is off) all have no {@code User.id} here — so the migrated field
     * would hold a <em>mixture</em> of the two identifier spaces, which is strictly worse than one
     * that consistently holds logins. And it is not two collections: {@code professional_application},
     * {@code device_token}, {@code message}, {@code message_recipient}, {@code patient_write_receipt},
     * {@code onboarding_event} and every {@code created_by} / {@code last_modified_by} in the
     * database hold the same string and would all have to move with it.
     *
     * <p>So this is <b>additive, nullable and never a lookup key</b>. Nothing resolves a caller by
     * it; {@link #accountId} remains the one identity this service reads and writes by. It exists to
     * be <em>published</em>, on {@code ProfileStatus}, so a directory holding the account half has
     * the id it already knows the clinician by.
     *
     * <p>Null means "not known yet", never "no account". It is filled only by
     * {@code OnboardingService.upsertOwnProfile}, from the caller's own {@code uid} claim on their
     * own profile, and <b>is never cleared once set</b> — a clinician whose 30-day token predates
     * the claim would otherwise wipe it on their next save and take the join down again.
     *
     * <p><b>READ-ONLY over HTTP, and that is not decoration.</b> This field asserts to another stack
     * which gateway account a clinician is, so a value a caller can choose is a value a caller can
     * forge. Without {@code READ_ONLY} any holder of {@code CLINICAL_MUTATION} — six roles — could
     * {@code PUT /api/profiles/&#123;someone-else&#125;} with their own {@code uid} in the body, and
     * this service would publish it to {@code hc.professional.registration} as that clinician's
     * account identifier; hc-admin keys {@code DirectoryLink.external_key} on exactly that value and
     * would link the wrong account. The service goes to lengths to stop an administrator's uid
     * reaching a clinician's row through the <em>token</em> — see
     * {@code OnboardingService.upsertOwnProfile} — and this closes the same door on the request body.
     * Found by the review of backlog item 48.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Indexed(sparse = true)
    @Field("account_uid")
    private String accountUid;

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

    public String getAccountUid() {
        return this.accountUid;
    }

    public Profile accountUid(String accountUid) {
        this.setAccountUid(accountUid);
        return this;
    }

    public void setAccountUid(String accountUid) {
        this.accountUid = accountUid;
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
