package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A PersonalDocument.
 */
@Document(collection = "personal_document")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class PersonalDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("name")
    private String name;

    @Field("profile_id")
    private String profileId;

    @Field("data")
    private byte[] data;

    @Field("data_content_type")
    private String dataContentType;

    @Field("type")
    private DocumentType type;

    @Field("created_date")
    private LocalDate createdDate;

    @Field("modified_date")
    private LocalDate modifiedDate;

    @Field("last_modified_by")
    private String lastModifiedBy;

    @Field("sha256_checksum")
    private String sha256Checksum;

    @Field("size_bytes")
    private Long sizeBytes;

    /** Required label when {@code type == DocumentType.OTHER}. */
    @Field("other_label")
    private String otherLabel;

    /** Required for licenses; drives the WP7 expiry sweep. */
    @Field("expiry_date")
    private LocalDate expiryDate;

    @Field("verification_status")
    private VerificationStatus verificationStatus;

    @Field("verified_by")
    private String verifiedBy;

    @Field("verified_at")
    private Instant verifiedAt;

    @Field("rejection_reason")
    private String rejectionReason;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public PersonalDocument id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public PersonalDocument name(String name) {
        this.setName(name);
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProfileId() {
        return this.profileId;
    }

    public PersonalDocument profileId(String profileId) {
        this.setProfileId(profileId);
        return this;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public byte[] getData() {
        return this.data;
    }

    public PersonalDocument data(byte[] data) {
        this.setData(data);
        return this;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getDataContentType() {
        return this.dataContentType;
    }

    public PersonalDocument dataContentType(String dataContentType) {
        this.dataContentType = dataContentType;
        return this;
    }

    public void setDataContentType(String dataContentType) {
        this.dataContentType = dataContentType;
    }

    public DocumentType getType() {
        return this.type;
    }

    public PersonalDocument type(DocumentType type) {
        this.setType(type);
        return this;
    }

    public void setType(DocumentType type) {
        this.type = type;
    }

    public LocalDate getCreatedDate() {
        return this.createdDate;
    }

    public PersonalDocument createdDate(LocalDate createdDate) {
        this.setCreatedDate(createdDate);
        return this;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDate getModifiedDate() {
        return this.modifiedDate;
    }

    public PersonalDocument modifiedDate(LocalDate modifiedDate) {
        this.setModifiedDate(modifiedDate);
        return this;
    }

    public void setModifiedDate(LocalDate modifiedDate) {
        this.modifiedDate = modifiedDate;
    }

    public String getLastModifiedBy() {
        return this.lastModifiedBy;
    }

    public PersonalDocument lastModifiedBy(String lastModifiedBy) {
        this.setLastModifiedBy(lastModifiedBy);
        return this;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public String getSha256Checksum() {
        return this.sha256Checksum;
    }

    public PersonalDocument sha256Checksum(String sha256Checksum) {
        this.setSha256Checksum(sha256Checksum);
        return this;
    }

    public void setSha256Checksum(String sha256Checksum) {
        this.sha256Checksum = sha256Checksum;
    }

    public Long getSizeBytes() {
        return this.sizeBytes;
    }

    public PersonalDocument sizeBytes(Long sizeBytes) {
        this.setSizeBytes(sizeBytes);
        return this;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getOtherLabel() {
        return this.otherLabel;
    }

    public PersonalDocument otherLabel(String otherLabel) {
        this.setOtherLabel(otherLabel);
        return this;
    }

    public void setOtherLabel(String otherLabel) {
        this.otherLabel = otherLabel;
    }

    public LocalDate getExpiryDate() {
        return this.expiryDate;
    }

    public PersonalDocument expiryDate(LocalDate expiryDate) {
        this.setExpiryDate(expiryDate);
        return this;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public VerificationStatus getVerificationStatus() {
        return this.verificationStatus;
    }

    public PersonalDocument verificationStatus(VerificationStatus verificationStatus) {
        this.setVerificationStatus(verificationStatus);
        return this;
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getVerifiedBy() {
        return this.verifiedBy;
    }

    public PersonalDocument verifiedBy(String verifiedBy) {
        this.setVerifiedBy(verifiedBy);
        return this;
    }

    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public Instant getVerifiedAt() {
        return this.verifiedAt;
    }

    public PersonalDocument verifiedAt(Instant verifiedAt) {
        this.setVerifiedAt(verifiedAt);
        return this;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getRejectionReason() {
        return this.rejectionReason;
    }

    public PersonalDocument rejectionReason(String rejectionReason) {
        this.setRejectionReason(rejectionReason);
        return this;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PersonalDocument)) {
            return false;
        }
        return getId() != null && getId().equals(((PersonalDocument) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "PersonalDocument{" +
            "id=" + getId() +
            ", name='" + getName() + "'" +
            ", profileId='" + getProfileId() + "'" +
            ", data='" + getData() + "'" +
            ", dataContentType='" + getDataContentType() + "'" +
            ", type='" + getType() + "'" +
            ", createdDate='" + getCreatedDate() + "'" +
            ", modifiedDate='" + getModifiedDate() + "'" +
            ", lastModifiedBy='" + getLastModifiedBy() + "'" +
            "}";
    }
}
