package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A Roster.
 */
@Document(collection = "roster")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class Roster implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("name")
    private String name;

    @Field("description")
    private String description;

    @Field("professional_id")
    private String professionalId;

    @Field("schedule")
    private LocalDate schedule;

    @Field("duration")
    private Duration duration;

    @Field("tasks")
    private String tasks;

    @Field("created_date")
    private String createdDate;

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

    public Roster id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public Roster name(String name) {
        this.setName(name);
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return this.description;
    }

    public Roster description(String description) {
        this.setDescription(description);
        return this;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProfessionalId() {
        return this.professionalId;
    }

    public Roster professionalId(String professionalId) {
        this.setProfessionalId(professionalId);
        return this;
    }

    public void setProfessionalId(String professionalId) {
        this.professionalId = professionalId;
    }

    public LocalDate getSchedule() {
        return this.schedule;
    }

    public Roster schedule(LocalDate schedule) {
        this.setSchedule(schedule);
        return this;
    }

    public void setSchedule(LocalDate schedule) {
        this.schedule = schedule;
    }

    public Duration getDuration() {
        return this.duration;
    }

    public Roster duration(Duration duration) {
        this.setDuration(duration);
        return this;
    }

    public void setDuration(Duration duration) {
        this.duration = duration;
    }

    public String getTasks() {
        return this.tasks;
    }

    public Roster tasks(String tasks) {
        this.setTasks(tasks);
        return this;
    }

    public void setTasks(String tasks) {
        this.tasks = tasks;
    }

    public String getCreatedDate() {
        return this.createdDate;
    }

    public Roster createdDate(String createdDate) {
        this.setCreatedDate(createdDate);
        return this;
    }

    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDate getModifiedDate() {
        return this.modifiedDate;
    }

    public Roster modifiedDate(LocalDate modifiedDate) {
        this.setModifiedDate(modifiedDate);
        return this;
    }

    public void setModifiedDate(LocalDate modifiedDate) {
        this.modifiedDate = modifiedDate;
    }

    public String getCreatedBy() {
        return this.createdBy;
    }

    public Roster createdBy(String createdBy) {
        this.setCreatedBy(createdBy);
        return this;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getModifiedBy() {
        return this.modifiedBy;
    }

    public Roster modifiedBy(String modifiedBy) {
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
        if (!(o instanceof Roster)) {
            return false;
        }
        return getId() != null && getId().equals(((Roster) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "Roster{" +
            "id=" + getId() +
            ", name='" + getName() + "'" +
            ", description='" + getDescription() + "'" +
            ", professionalId='" + getProfessionalId() + "'" +
            ", schedule='" + getSchedule() + "'" +
            ", duration='" + getDuration() + "'" +
            ", tasks='" + getTasks() + "'" +
            ", createdDate='" + getCreatedDate() + "'" +
            ", modifiedDate='" + getModifiedDate() + "'" +
            ", createdBy='" + getCreatedBy() + "'" +
            ", modifiedBy='" + getModifiedBy() + "'" +
            "}";
    }
}
