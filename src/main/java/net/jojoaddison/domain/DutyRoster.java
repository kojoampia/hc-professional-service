package net.jojoaddison.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A professional duty-roster assignment, and since DR2 a <b>round of visits</b>
 * (professional-onboarding-workflow.md § Duty roster; docs/duty-roster.md §§ 4–6).
 *
 * <p>Assignment-only by decision: roster administrators create these; professionals read their own.
 * DR4 adds the one exception, where a professional may request an absence against their own roster.
 *
 * <p><b>The separation rule stated here until DR2 is reversed, deliberately.</b> This javadoc read
 * "deliberately carries no patientId — professional scheduling stays separate from patient care
 * scheduling", and {@link Visit#getCustomerId()} now carries exactly that link. The reason is
 * concrete rather than a relaxation of principle: this is a mobile health service, and <b>a
 * professional cannot perform a home visit without the customer's address.</b> Scheduling a round
 * without naming who it serves is not a stricter design, it is an unusable one. The corresponding
 * paragraph in the onboarding spec was changed in the same commit — if you are reading this because
 * a comment somewhere still asserts the old rule, that comment is the stale one.
 *
 * <p>What has <em>not</em> changed is where patient data lives. This service stores a customer id and
 * a short-lived display snapshot; demographics, cases, medications and reports remain patientservice's,
 * read across through {@code PatientServiceClient} with the caller's own token.
 */
@Document(collection = "duty_roster")
/*
 * (professional_id, date) — every read this service performs on the collection is "this
 * professional's roster", optionally narrowed to a date range or a year, so the compound index
 * matches the access pattern the way the standalone professional_id index alone could not. Date is
 * second because it is the range half; the equality half leads.
 */
@CompoundIndex(name = "professional_date_idx", def = "{'professional_id': 1, 'date': 1}")
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

    /**
     * The round: the customers this shift serves, each with its own clock times (DR2).
     *
     * <p>Never null — an empty round is a valid one, and callers iterate this without checking.
     * Ordering is not enforced on write; the day view sorts by resolved start time, which is the only
     * ordering that survives the {@code NIGHT} midnight wrap.
     */
    @Valid
    @Field("visits")
    private List<Visit> visits = new ArrayList<>();

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

    public List<Visit> getVisits() {
        return this.visits;
    }

    public DutyRoster visits(List<Visit> visits) {
        setVisits(visits);
        return this;
    }

    /** A null list is normalised to empty — see the field javadoc; callers never null-check it. */
    public void setVisits(List<Visit> visits) {
        this.visits = visits == null ? new ArrayList<>() : visits;
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

    /**
     * <b>Deliberately omits the visits, and must keep doing so.</b>
     *
     * <p>Only the count appears. This service logs assignments on the write path, so a {@code
     * toString} that rendered the round would put customer names, addresses and phone numbers into
     * the logs of a stack that holds them only as a short-lived display snapshot — and log retention
     * is not the 90-day purge. {@code DutyRosterVisitPrivacyTest} asserts this. (This said
     * {@code ...IT}, which did not exist until item 7 added one — and that one asserts response
     * bodies, not this method. The two sit side by side because they guard different channels.)
     */
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
                ", visits=" + getVisits().size() +
                "}";
    }
}
