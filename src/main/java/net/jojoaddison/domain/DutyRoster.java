package net.jojoaddison.domain;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDate;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A professional duty-roster assignment (professional-onboarding-workflow.md
 * § Duty roster). Assignment-only by decision: roster administrators create
 * these; professionals read their own. Deliberately carries no patientId —
 * professional scheduling stays separate from patient care scheduling.
 */
@Document(collection = "duty_roster")
public class DutyRoster extends AbstractAuditingEntity<String> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @NotNull
    @Field("date")
    private LocalDate date;

    @NotNull
    @Field("duty")
    private DutyRole duty;

    /** Profile id of the assigned professional. */
    @NotNull
    @Indexed
    @Field("professional_id")
    private String professionalId;

    @NotNull
    @Field("shift")
    private ShiftType shift;

    @NotNull
    @Field("name")
    private String name;

    @Field("description")
    private String description;

    @Override
    public String getId() {
        return this.id;
    }

    public DutyRoster id(String id) {
        this.id = id;
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDate getDate() {
        return this.date;
    }

    public DutyRoster date(LocalDate date) {
        this.date = date;
        return this;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public DutyRole getDuty() {
        return this.duty;
    }

    public DutyRoster duty(DutyRole duty) {
        this.duty = duty;
        return this;
    }

    public void setDuty(DutyRole duty) {
        this.duty = duty;
    }

    public String getProfessionalId() {
        return this.professionalId;
    }

    public DutyRoster professionalId(String professionalId) {
        this.professionalId = professionalId;
        return this;
    }

    public void setProfessionalId(String professionalId) {
        this.professionalId = professionalId;
    }

    public ShiftType getShift() {
        return this.shift;
    }

    public DutyRoster shift(ShiftType shift) {
        this.shift = shift;
        return this;
    }

    public void setShift(ShiftType shift) {
        this.shift = shift;
    }

    public String getName() {
        return this.name;
    }

    public DutyRoster name(String name) {
        this.name = name;
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return this.description;
    }

    public DutyRoster description(String description) {
        this.description = description;
        return this;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DutyRoster)) {
            return false;
        }
        return getId() != null && getId().equals(((DutyRoster) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "DutyRoster{" +
                "id=" + getId() +
                ", date='" + getDate() + "'" +
                ", duty='" + getDuty() + "'" +
                ", professionalId='" + getProfessionalId() + "'" +
                ", shift='" + getShift() + "'" +
                ", name='" + getName() + "'" +
                "}";
    }
}
