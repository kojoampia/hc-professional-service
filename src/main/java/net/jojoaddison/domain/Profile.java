package net.jojoaddison.domain;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.Sex;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
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

    @Field("title")
    private String title;

    @Field("first_name")
    private String firstName;

    @Field("middle_names")
    private String middleNames;

    @Field("last_name")
    private String lastName;

    @Field("birth_date")
    private LocalDate birthDate;

    /**
     * {@code FEMALE} or {@code MALE}, and nothing else — typed since F9 (profile.md's Profile model
     * types this field {@code enum} and names {@code sex.enum.ts - &#123;'FEMALE','MALE'&#125;}).
     *
     * <p><b>It was a free-text {@code String}</b>, so {@code &#123;"sex":"banana"&#125;} stored and
     * answered 200 — and {@code OnboardingService.personalDetailsComplete} counted it as provided,
     * because the only thing it could check was that there was text. An invalid value is now refused
     * with a 400 by the binding rather than persisted; {@code ProfileEnumValueMigration} deals with
     * what was already stored.
     */
    @Field("sex")
    private Sex sex;

    @Field("mobile_phone")
    private String mobilePhone;

    @Field("phone_number")
    private String phoneNumber;

    @Field("email")
    private String email;

    @Field("address")
    private Address address;

    /**
     * Which identity document {@link #cardNumber} is the number of — typed since F9.
     *
     * <p>{@code profile.md} types this field {@code enum} and names its vocabulary explicitly:
     * <b>{@code PersonalDocumentType: types.enum.ts}</b>. That is {@link DocumentType} on this side
     * — the same nine members {@code PersonalDocument.type} already uses, which is the point: a card
     * type and a document type are the same vocabulary and were two different ones (one of them
     * free text) until this change. {@code &#123;"cardType":"loyalty card"&#125;} stored and
     * answered 200.
     *
     * <p>⚠ It is deliberately <b>not</b> narrowed to {@code IDENTITY_TYPES}, the four
     * {@code OnboardingService} accepts as government identity. The specification names the whole
     * enumeration, and a narrower type here would make a value the specification admits unstorable.
     */
    @Field("card_type")
    private DocumentType cardType;

    @Field("card_number")
    private String cardNumber;

    /**
     * The clinician's next of kin — <b>a list since profile.md's T1</b>, where it was a single
     * embedded {@code emergencyContact}.
     *
     * <p>{@code profile.md} specifies {@code contacts: EmergencyContact[]} and requires <b>at least
     * two</b> of them, which a single embedded object cannot express at all.
     * {@code EmergencyContactListMigration} remaps every document written in the old shape into a
     * one-element list; {@code OnboardingService.nextOfKinComplete} is the server's own predicate
     * over it and says there what "complete" means for a list.
     *
     * <p><b>Deliberately NOT initialised to an empty list, unlike {@link #teamIds} beside it.</b>
     * That is the single most expensive line to get wrong here, and the argument is already written
     * out in {@code ProfilePatchFieldCoverageIT.anAbsentTeamIdsIsNotAChange}: an initialised
     * collection is <em>never null</em> on a bound {@code Profile}, so the {@code != null} guard
     * {@code ProfileService.applyProvidedFields} uses would fire on every partial write and
     * <b>empty a clinician's next of kin whenever they changed a phone number</b>. Left null,
     * "the caller sent no contacts" and "the caller sent an empty list" stay distinguishable on the
     * only path that matters. A getter that answered an empty list instead of null would reintroduce
     * exactly the same defect one layer up.
     */
    @Field("contacts")
    private List<EmergencyContact> contacts;

    @Field("status")
    private ProfileStatus status;

    @Field("specialty_category_id")
    private String specialtyCategoryId;

    @Field("team_ids")
    private List<String> teamIds = new ArrayList<>();

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
     * saves a {@code Profile} — {@code ProfileService}, the repository directly — stamps them
     * without knowing it has to.
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

    public Sex getSex() {
        return this.sex;
    }

    public Profile sex(Sex sex) {
        this.setSex(sex);
        return this;
    }

    public void setSex(Sex sex) {
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

    public DocumentType getCardType() {
        return this.cardType;
    }

    public Profile cardType(DocumentType cardType) {
        this.setCardType(cardType);
        return this;
    }

    public void setCardType(DocumentType cardType) {
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

    public List<EmergencyContact> getContacts() {
        return this.contacts;
    }

    public Profile contacts(List<EmergencyContact> contacts) {
        this.setContacts(contacts);
        return this;
    }

    public void setContacts(List<EmergencyContact> contacts) {
        this.contacts = contacts;
    }

    /**
     * The singular {@code emergencyContact} this document carried until profile.md's T1, kept
     * <b>on the wire only</b> so the clients that have not migrated yet keep working.
     *
     * <h2>Why a compatibility shim rather than a clean break — the reason, corrected (F-E)</h2>
     *
     * <p>⛔ <b>The justification that stood here had outlived its subject.</b> It read:
     *
     * <blockquote><i>"{@code PUT /api/onboarding/profile} is still live and still the path two
     * shipped clients write a profile through."</i></blockquote>
     *
     * <p><b>That path is gone.</b> F8 retired {@code GET} and {@code PUT /api/onboarding/profile} per
     * {@code profile.md} § Other Elements (<i>"{@code api/onboarding/profile} should migrate to
     * {@code api/profile}"</i>); {@code OnboardingResource} now carries only {@code /progress} and the
     * two {@code /acknowledgement} verbs, and {@code OnboardingService} four files away says so in as
     * many words. Keeping a deprecated alias on the strength of a retired endpoint is the same shape
     * as the false cross-product claim F-C deleted from {@code DomainEventPublisher}: a reason that
     * reads as current because nobody re-checked its subject.
     *
     * <p>⭐ <b>The alias is still needed, and the mechanism is different: the clients name it on the
     * NEW path.</b> Measured 2026-10-09 in both frontends — each builds its URL through
     * {@code getEndpointFor('api/profile', 'professionalservice')} and each still speaks
     * {@code emergencyContact}:
     *
     * <table>
     *   <caption>The two live consumers of this alias</caption>
     *   <tr><th>client</th><th>file</th><th>what it does</th></tr>
     *   <tr><td>{@code mobile/}</td><td>{@code src/app/features/me/me.page.ts}</td>
     *       <td>reads {@code profile?.emergencyContact?.name} / {@code .relationship} /
     *           {@code .phone} and PUTs {@code emergencyContact: {…}} back</td></tr>
     *   <tr><td>{@code web/}</td><td>{@code app/account/profile/clinical-profile.component.ts}</td>
     *       <td>the same read and the same write, through
     *           {@code OnboardingApiService}'s {@code profileUrl}</td></tr>
     * </table>
     *
     * <p>⚠ <b>Two clients, not one</b> — F-E named only {@code mobile/}, and {@code web/}'s
     * {@code ClinicalProfileComponent} is a second. {@code OnboardingApiService} records the pending
     * move in its own comment. Dropping the name from the wire today would leave <em>both</em>
     * next-of-kin forms silently unable to save: a 200 with the field quietly gone, which is
     * precisely the "answered wrongly" failure this repository keeps closing.
     *
     * <p><b>It is a projection of {@link #contacts}, never a second stored field.</b> There is one
     * {@code contacts} array in Mongo and no {@code emergency_contact} key after
     * {@code EmergencyContactListMigration} runs. The read answers the first contact; the write puts
     * one contact into the list.
     *
     * <p><b>{@code contacts} wins when a body carries both</b>, whichever order Jackson binds them
     * in — the setter below only fills a list that is still empty. Without that guard a client
     * round-tripping a document it had just read would have the outcome depend on field order in the
     * JSON, which is not a contract anybody could rely on.
     *
     * <p>⛔ <b>Not reflected by {@code ProfilePatchFieldCoverageIT}</b>, which enumerates declared
     * fields and sees no such field. The alias has its own named cases there instead. Retire this
     * pair with T6, and only once <b>both</b> clients in the table above have been moved — the
     * retirement is gated on the clients, which is a thing to measure, and no longer on an endpoint,
     * which was a thing that had already happened.
     *
     * @deprecated use {@link #getContacts()}; retires with the last client that names it (T6).
     */
    @Deprecated(since = "profile.md T1")
    @Transient
    @JsonGetter("emergencyContact")
    public EmergencyContact getEmergencyContact() {
        return this.contacts == null || this.contacts.isEmpty() ? null : this.contacts.get(0);
    }

    /** @deprecated see {@link #getEmergencyContact()}. */
    @Deprecated(since = "profile.md T1")
    @JsonSetter("emergencyContact")
    public void setEmergencyContact(EmergencyContact emergencyContact) {
        if (this.contacts != null && !this.contacts.isEmpty()) {
            // `contacts` was bound first and is the newer name; it wins. See the note above.
            return;
        }
        this.contacts = emergencyContact == null ? null : new ArrayList<>(List.of(emergencyContact));
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

    public ProfileStatus getStatus() {
        return this.status;
    }

    public Profile status(ProfileStatus status) {
        this.setStatus(status);
        return this;
    }

    public void setStatus(ProfileStatus status) {
        this.status = status;
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
                ", contacts='" + getContacts() + "'" +
                ", specialtyCategoryId='" + getSpecialtyCategoryId() + "'" +
                ", teamIds='" + getTeamIds() + "'" +
                ", status='" + getStatus() + "'" +
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
