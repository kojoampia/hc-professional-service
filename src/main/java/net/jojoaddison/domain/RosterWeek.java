package net.jojoaddison.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * PDF: Roster (Name, Description, Patient, Date) — modelled as a
 * week header so the grid in the demo has something to publish.
 */
@Schema(
    description = "PDF: Roster (Name, Description, Patient, Date) — modelled as a\nweek header so the grid in the demo has something to publish."
)
@Document(collection = "roster_week")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class RosterWeek implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @NotNull
    @Size(max = 60)
    @Field("label")
    private String label;

    @NotNull
    @Field("start_date")
    private LocalDate startDate;

    @NotNull
    @Field("published")
    private Boolean published;

    @Field("published_at")
    private Instant publishedAt;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public String getId() {
        return this.id;
    }

    public RosterWeek id(String id) {
        this.setId(id);
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return this.label;
    }

    public RosterWeek label(String label) {
        this.setLabel(label);
        return this;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LocalDate getStartDate() {
        return this.startDate;
    }

    public RosterWeek startDate(LocalDate startDate) {
        this.setStartDate(startDate);
        return this;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public Boolean getPublished() {
        return this.published;
    }

    public RosterWeek published(Boolean published) {
        this.setPublished(published);
        return this;
    }

    public void setPublished(Boolean published) {
        this.published = published;
    }

    public Instant getPublishedAt() {
        return this.publishedAt;
    }

    public RosterWeek publishedAt(Instant publishedAt) {
        this.setPublishedAt(publishedAt);
        return this;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RosterWeek)) {
            return false;
        }
        return getId() != null && getId().equals(((RosterWeek) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "RosterWeek{" +
            "id=" + getId() +
            ", label='" + getLabel() + "'" +
            ", startDate='" + getStartDate() + "'" +
            ", published='" + getPublished() + "'" +
            ", publishedAt='" + getPublishedAt() + "'" +
            "}";
    }
}
