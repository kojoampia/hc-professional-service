package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A Task.
 */
@Document(collection = "task")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class Task implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("name")
    private String name;

    @Field("description")
    private String description;

    @Field("schedule")
    private LocalDate schedule;

    @Field("duration")
    private Duration duration;

    @Field("attendant_id")
    private String attendantId;

    @Field("team_id")
    private String teamId;

    @Field("patient_id")
    private String patientId;

    @Field("attendant")
    private String attendant;

    @Field("created_date")
    private LocalDate createdDate;

    @Field("modified_date")
    private LocalDate modifiedDate;

    @Field("created_by")
    private String createdBy;

    @Field("modified_by")
    private String modifiedBy;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public Task id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public Task name(String name) {
        this.setName(name);
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return this.description;
    }

    public Task description(String description) {
        this.setDescription(description);
        return this;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getSchedule() {
        return this.schedule;
    }

    public Task schedule(LocalDate schedule) {
        this.setSchedule(schedule);
        return this;
    }

    public void setSchedule(LocalDate schedule) {
        this.schedule = schedule;
    }

    public Duration getDuration() {
        return this.duration;
    }

    public Task duration(Duration duration) {
        this.setDuration(duration);
        return this;
    }

    public void setDuration(Duration duration) {
        this.duration = duration;
    }

    public String getAttendantId() {
        return this.attendantId;
    }

    public Task attendantId(String attendantId) {
        this.setAttendantId(attendantId);
        return this;
    }

    public void setAttendantId(String attendantId) {
        this.attendantId = attendantId;
    }

    public String getTeamId() {
        return this.teamId;
    }

    public Task teamId(String teamId) {
        this.setTeamId(teamId);
        return this;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getPatientId() {
        return this.patientId;
    }

    public Task patientId(String patientId) {
        this.setPatientId(patientId);
        return this;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getAttendant() {
        return this.attendant;
    }

    public Task attendant(String attendant) {
        this.setAttendant(attendant);
        return this;
    }

    public void setAttendant(String attendant) {
        this.attendant = attendant;
    }

    public LocalDate getCreatedDate() {
        return this.createdDate;
    }

    public Task createdDate(LocalDate createdDate) {
        this.setCreatedDate(createdDate);
        return this;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDate getModifiedDate() {
        return this.modifiedDate;
    }

    public Task modifiedDate(LocalDate modifiedDate) {
        this.setModifiedDate(modifiedDate);
        return this;
    }

    public void setModifiedDate(LocalDate modifiedDate) {
        this.modifiedDate = modifiedDate;
    }

    public String getCreatedBy() {
        return this.createdBy;
    }

    public Task createdBy(String createdBy) {
        this.setCreatedBy(createdBy);
        return this;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getModifiedBy() {
        return this.modifiedBy;
    }

    public Task modifiedBy(String modifiedBy) {
        this.setModifiedBy(modifiedBy);
        return this;
    }

    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Task)) {
            return false;
        }
        return getId() != null && getId().equals(((Task) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "Task{" +
            "id=" + getId() +
            ", name='" + getName() + "'" +
            ", description='" + getDescription() + "'" +
            ", schedule='" + getSchedule() + "'" +
            ", duration='" + getDuration() + "'" +
            ", attendantId='" + getAttendantId() + "'" +
            ", teamId='" + getTeamId() + "'" +
            ", patientId='" + getPatientId() + "'" +
            ", attendant='" + getAttendant() + "'" +
            ", createdDate='" + getCreatedDate() + "'" +
            ", modifiedDate='" + getModifiedDate() + "'" +
            ", createdBy='" + getCreatedBy() + "'" +
            ", modifiedBy='" + getModifiedBy() + "'" +
            "}";
    }
}
