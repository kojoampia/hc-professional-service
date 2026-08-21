package net.jojoaddison.domain;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDate;
import net.jojoaddison.domain.enumeration.AbsenceStatus;
import net.jojoaddison.domain.enumeration.AbsenceType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Time a professional is away (docs/duty-roster.md § 8, DR4).
 *
 * <p>Day-level and inclusive at both ends: an absence from the 3rd to the 5th is three days off, not
 * two. There are no times on it because there is no such thing as half a shift here — the roster's
 * unit is a shift, so the absence's unit is a day.
 *
 * <p><b>This is the first thing a professional may write against their own roster</b>, and it is a
 * deliberate, scoped exception to the assignment-only policy rather than a softening of it. They may
 * request; only a roster administrator may grant. The spec paragraph that says professionals "read
 * and never write" was amended in the same change — see
 * {@code professional-onboarding-workflow.md} § Duty roster.
 *
 * <p>The record carries no reason text. What a roster needs is whether the day can be filled; why
 * someone is away is a conversation, and a free-text field on a calendar entry is where medical
 * details end up in a system that has no business holding them.
 */
@Document(collection = "absence")
/*
 * (professional_id, from_date) — every read is "this professional's absences", narrowed to a range or
 * a year, exactly as with duty_roster. The equality half leads; the range half follows.
 */
@CompoundIndex(name = "absence_professional_from_idx", def = "{'professional_id': 1, 'from_date': 1}")
public class Absence extends AbstractAuditingEntity<String> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** Profile id of the absent professional — the same identifier {@link DutyRoster} uses. */
    @NotNull
    @Field("professional_id")
    private String professionalId;

    @NotNull
    @Field("from_date")
    private LocalDate fromDate;

    /** Inclusive. Equal to {@code fromDate} for a single day off. */
    @NotNull
    @Field("to_date")
    private LocalDate toDate;

    @NotNull
    @Field("type")
    private AbsenceType type;

    @NotNull
    @Field("status")
    private AbsenceStatus status = AbsenceStatus.REQUESTED;

    @Override
    public String getId() {
        return this.id;
    }

    public Absence id(String id) {
        this.id = id;
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProfessionalId() {
        return this.professionalId;
    }

    public Absence professionalId(String professionalId) {
        this.professionalId = professionalId;
        return this;
    }

    public void setProfessionalId(String professionalId) {
        this.professionalId = professionalId;
    }

    public LocalDate getFromDate() {
        return this.fromDate;
    }

    public Absence fromDate(LocalDate fromDate) {
        this.fromDate = fromDate;
        return this;
    }

    public void setFromDate(LocalDate fromDate) {
        this.fromDate = fromDate;
    }

    public LocalDate getToDate() {
        return this.toDate;
    }

    public Absence toDate(LocalDate toDate) {
        this.toDate = toDate;
        return this;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    public AbsenceType getType() {
        return this.type;
    }

    public Absence type(AbsenceType type) {
        this.type = type;
        return this;
    }

    public void setType(AbsenceType type) {
        this.type = type;
    }

    public AbsenceStatus getStatus() {
        return this.status;
    }

    public Absence status(AbsenceStatus status) {
        this.status = status;
        return this;
    }

    public void setStatus(AbsenceStatus status) {
        this.status = status;
    }

    /** True if the absence covers this date. Inclusive at both ends — see {@link #getToDate()}. */
    public boolean covers(LocalDate date) {
        return date != null && fromDate != null && toDate != null && !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Absence)) {
            return false;
        }
        return getId() != null && getId().equals(((Absence) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "Absence{" +
                "id=" + getId() +
                ", professionalId='" + getProfessionalId() + "'" +
                ", fromDate='" + getFromDate() + "'" +
                ", toDate='" + getToDate() + "'" +
                ", type='" + getType() + "'" +
                ", status='" + getStatus() + "'" +
                "}";
    }
}
