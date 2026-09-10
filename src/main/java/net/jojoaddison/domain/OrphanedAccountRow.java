package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A row whose account key named a login that no gateway account answers to — recorded here by
 * {@code AccountIdMigrationService} at the moment its key was cleared (backlog.md item 50).
 *
 * <p><b>This collection is the answer to the question item 48 declined the whole migration over.</b>
 * The login-to-{@code User.id} mapping is not total: an account since deleted, and a login arriving
 * on a token minted by hc-admin or hc-patient, both have rows in this database and no
 * {@code User.id} anywhere. Item 50 says the gap must be closed rather than routed around, and
 * forbids the two closures that suggest themselves — synthesising an id, and leaving the login in a
 * field that has stopped meaning login. Both would put a value in the estate's correlation key that
 * the estate cannot correlate on, which is the defect the item exists to remove.
 *
 * <p>So the third answer: <b>the key is cleared and the old value is written down here.</b> The row
 * stops claiming an identity it cannot substantiate — every ownership check refuses it, and
 * {@code ProfileStatus} omits the subject rather than announcing a name nobody can place — while the
 * only copy of what it used to say survives for a person to reconcile. Clearing without recording
 * would destroy that copy; recording without clearing would leave the second identifier in place.
 *
 * <p><b>Not an error log.</b> A log line is rotated away and cannot be queried, and the reconciliation
 * this supports — did that clinician's account get deleted, or was that a sibling stack's token? — is
 * asked days later. It is deliberately append-only and deliberately not read by any request path.
 *
 * <p>Carries no personal data beyond the login itself, which is the value under discussion.
 */
@Document(collection = "orphaned_account_row")
public class OrphanedAccountRow implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** The Mongo collection the row lives in, e.g. {@code profile}. */
    @Field("collection_name")
    private String collectionName;

    /** The document whose key was cleared. */
    @Field("document_id")
    private String documentId;

    /** The field that was cleared, e.g. {@code accountId}. */
    @Field("field_name")
    private String fieldName;

    /** What the field held — a login the gateway does not know. */
    @Field("orphaned_value")
    private String orphanedValue;

    @Field("detected_at")
    private Instant detectedAt;

    /**
     * The {@code profile.account_uid} this row carried when it was quarantined, or null.
     *
     * <p>Recorded because {@code dropRetiredAccountUid} deletes that field immediately after the
     * migration, and for a quarantined row this is the only place the value survives. It did not
     * resolve — a resolving one would have rewritten the row rather than quarantining it — but a
     * stale id still names <em>which</em> account, which a dead login may no longer.
     */
    private String carriedAccountUid;

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public OrphanedAccountRow id(String id) {
        this.setId(id);
        return this;
    }

    public String getCollectionName() {
        return this.collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public OrphanedAccountRow collectionName(String collectionName) {
        this.setCollectionName(collectionName);
        return this;
    }

    public String getDocumentId() {
        return this.documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public OrphanedAccountRow documentId(String documentId) {
        this.setDocumentId(documentId);
        return this;
    }

    public String getFieldName() {
        return this.fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public OrphanedAccountRow fieldName(String fieldName) {
        this.setFieldName(fieldName);
        return this;
    }

    public String getOrphanedValue() {
        return this.orphanedValue;
    }

    public void setOrphanedValue(String orphanedValue) {
        this.orphanedValue = orphanedValue;
    }

    public OrphanedAccountRow orphanedValue(String orphanedValue) {
        this.setOrphanedValue(orphanedValue);
        return this;
    }

    public Instant getDetectedAt() {
        return this.detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }

    public OrphanedAccountRow detectedAt(Instant detectedAt) {
        this.setDetectedAt(detectedAt);
        return this;
    }

    public String getCarriedAccountUid() {
        return carriedAccountUid;
    }

    public void setCarriedAccountUid(String carriedAccountUid) {
        this.carriedAccountUid = carriedAccountUid;
    }

    public OrphanedAccountRow carriedAccountUid(String carriedAccountUid) {
        this.carriedAccountUid = carriedAccountUid;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrphanedAccountRow other)) {
            return false;
        }
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "OrphanedAccountRow{" +
            "id=" + getId() +
            ", collectionName='" + getCollectionName() + "'" +
            ", documentId='" + getDocumentId() + "'" +
            ", fieldName='" + getFieldName() + "'" +
            ", detectedAt='" + getDetectedAt() + "'" +
            "}";
    }
}
