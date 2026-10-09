package net.jojoaddison.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import net.jojoaddison.domain.enumeration.ShiftType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One cell of the duty-roster grid.
 */
@Schema(description = "One cell of the duty-roster grid.")
@Document(collection = "shift_assignment")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class ShiftAssignment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @NotNull
    @Min(value = 0)
    @Max(value = 6)
    @Field("day_index")
    private Integer dayIndex;

    @NotNull
    @Field("shift_date")
    private LocalDate shiftDate;

    @NotNull
    @Field("shift")
    private ShiftType shift;

    // No @JsonIgnoreProperties: it named "assignments", which was the week -> assignments -> week
    // cycle it existed to break, and RosterWeek no longer has that collection. Left in place the
    // annotation would read as if the cycle were still there.
    @DBRef
    @Field("week")
    private RosterWeek week;

    @DBRef
    @Field("professional")
    @JsonIgnoreProperties(value = { "profile", "assignments", "team", "hub" }, allowSetters = true)
    private Professional professional;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public ShiftAssignment id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getDayIndex() {
        return this.dayIndex;
    }

    public ShiftAssignment dayIndex(Integer dayIndex) {
        this.setDayIndex(dayIndex);
        return this;
    }

    public void setDayIndex(Integer dayIndex) {
        this.dayIndex = dayIndex;
    }

    public LocalDate getShiftDate() {
        return this.shiftDate;
    }

    public ShiftAssignment shiftDate(LocalDate shiftDate) {
        this.setShiftDate(shiftDate);
        return this;
    }

    public void setShiftDate(LocalDate shiftDate) {
        this.shiftDate = shiftDate;
    }

    public ShiftType getShift() {
        return this.shift;
    }

    public ShiftAssignment shift(ShiftType shift) {
        this.setShift(shift);
        return this;
    }

    public void setShift(ShiftType shift) {
        this.shift = shift;
    }

    public RosterWeek getWeek() {
        return this.week;
    }

    public void setWeek(RosterWeek rosterWeek) {
        this.week = rosterWeek;
    }

    public ShiftAssignment week(RosterWeek rosterWeek) {
        this.setWeek(rosterWeek);
        return this;
    }

    public Professional getProfessional() {
        return this.professional;
    }

    public void setProfessional(Professional professional) {
        this.professional = professional;
    }

    public ShiftAssignment professional(Professional professional) {
        this.setProfessional(professional);
        return this;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ShiftAssignment)) {
            return false;
        }
        return getId() != null && getId().equals(((ShiftAssignment) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "ShiftAssignment{" +
            "id=" + getId() +
            ", dayIndex=" + getDayIndex() +
            ", shiftDate='" + getShiftDate() + "'" +
            ", shift='" + getShift() + "'" +
            "}";
    }
}
