package net.jojoaddison.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One recorded verification decision about a professional.
 *
 * <p><strong>Append-only.</strong> A change of state is a new row; no row is ever edited, and the
 * resource exposes no {@code PUT}, {@code PATCH} or {@code DELETE}. It is the {@link WageRate} idiom
 * for the same reason: a rate is effective-dated so a rise never restates a wage bill already paid,
 * and a verification history never restates who was credentialed when — which is the property that
 * matters if anybody ever has to answer for having let a professional onto a roster.
 *
 * <p><strong>Current state is projected onto {@code Professional.verification}</strong> by
 * {@code ProfessionalVerificationService}, which is the only thing permitted to write that field.
 * That projection is not the unmaintained counter {@code ServicePlan.subscriberCount} was — it has
 * exactly one write path, and this collection is the record behind it. Deriving the badge and the
 * dashboard's pending count from the history on every read was the alternative and was rejected on
 * cost: it turns a field read into an aggregation, on a list, to remove a projection nothing else
 * touches.
 *
 * <p>{@code recordedAt} and {@code recordedBy} are stamped by the service from the clock and the
 * token, and are on the DTO as read-only. A caller that could name the time or the author of a
 * verification could write a history that never happened, which is the one thing a history is for.
 */
@Schema(description = "One recorded verification decision about a professional. Append-only.")
@Document(collection = "professional_verification")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class ProfessionalVerification implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @NotNull
    @Field("status")
    private VerificationStatus status;

    @NotNull
    @Field("recorded_at")
    private Instant recordedAt;

    @NotNull
    @Size(max = 100)
    @Field("recorded_by")
    private String recordedBy;

    /** How it was checked — "Licence register", "Document review". Free text, because the sources vary. */
    @Size(max = 60)
    @Field("method")
    private String method;

    /** The register entry, ticket or document this decision rests on. */
    @Size(max = 120)
    @Field("reference")
    private String reference;

    @Size(max = 500)
    @Field("note")
    private String note;

    /**
     * When this verification lapses, if it does.
     *
     * <p>Nothing expires a row automatically today — an {@code EXPIRED} row is written like any
     * other. This records the date so that a scheduled sweep, or a person reading the record, can
     * see one coming.
     */
    @Field("expires_on")
    private LocalDate expiresOn;

    @DBRef
    @Field("professional")
    @JsonIgnoreProperties(value = { "profile", "assignments", "team", "hub" }, allowSetters = true)
    private Professional professional;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public ProfessionalVerification id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public VerificationStatus getStatus() {
        return this.status;
    }

    public ProfessionalVerification status(VerificationStatus status) {
        this.setStatus(status);
        return this;
    }

    public void setStatus(VerificationStatus status) {
        this.status = status;
    }

    public Instant getRecordedAt() {
        return this.recordedAt;
    }

    public ProfessionalVerification recordedAt(Instant recordedAt) {
        this.setRecordedAt(recordedAt);
        return this;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }

    public String getRecordedBy() {
        return this.recordedBy;
    }

    public ProfessionalVerification recordedBy(String recordedBy) {
        this.setRecordedBy(recordedBy);
        return this;
    }

    public void setRecordedBy(String recordedBy) {
        this.recordedBy = recordedBy;
    }

    public String getMethod() {
        return this.method;
    }

    public ProfessionalVerification method(String method) {
        this.setMethod(method);
        return this;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getReference() {
        return this.reference;
    }

    public ProfessionalVerification reference(String reference) {
        this.setReference(reference);
        return this;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getNote() {
        return this.note;
    }

    public ProfessionalVerification note(String note) {
        this.setNote(note);
        return this;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDate getExpiresOn() {
        return this.expiresOn;
    }

    public ProfessionalVerification expiresOn(LocalDate expiresOn) {
        this.setExpiresOn(expiresOn);
        return this;
    }

    public void setExpiresOn(LocalDate expiresOn) {
        this.expiresOn = expiresOn;
    }

    public Professional getProfessional() {
        return this.professional;
    }

    public void setProfessional(Professional professional) {
        this.professional = professional;
    }

    public ProfessionalVerification professional(Professional professional) {
        this.setProfessional(professional);
        return this;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfessionalVerification)) {
            return false;
        }
        return getId() != null && getId().equals(((ProfessionalVerification) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return (
            "ProfessionalVerification{" +
            "id=" +
            getId() +
            ", status='" +
            getStatus() +
            "'" +
            ", recordedAt='" +
            getRecordedAt() +
            "'" +
            ", recordedBy='" +
            getRecordedBy() +
            "'" +
            ", method='" +
            getMethod() +
            "'" +
            ", reference='" +
            getReference() +
            "'" +
            ", note='" +
            getNote() +
            "'" +
            ", expiresOn='" +
            getExpiresOn() +
            "'" +
            "}"
        );
    }
}
