package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Append-only audit record of an onboarding transition (onboarding workflow
 * § Status model). Events are written once and never updated or deleted —
 * the WP3 service layer must expose no mutation path for them.
 */
@Document(collection = "onboarding_event")
public class OnboardingEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Indexed
    @Field("application_id")
    private String applicationId;

    @Field("actor")
    private String actor;

    @Field("from_status")
    private OnboardingStatus fromStatus;

    @Field("to_status")
    private OnboardingStatus toStatus;

    @Field("reason")
    private String reason;

    @Field("at")
    private Instant at;

    public String getId() {
        return this.id;
    }

    public OnboardingEvent id(String id) {
        this.id = id;
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApplicationId() {
        return this.applicationId;
    }

    public OnboardingEvent applicationId(String applicationId) {
        this.applicationId = applicationId;
        return this;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String getActor() {
        return this.actor;
    }

    public OnboardingEvent actor(String actor) {
        this.actor = actor;
        return this;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public OnboardingStatus getFromStatus() {
        return this.fromStatus;
    }

    public OnboardingEvent fromStatus(OnboardingStatus fromStatus) {
        this.fromStatus = fromStatus;
        return this;
    }

    public void setFromStatus(OnboardingStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public OnboardingStatus getToStatus() {
        return this.toStatus;
    }

    public OnboardingEvent toStatus(OnboardingStatus toStatus) {
        this.toStatus = toStatus;
        return this;
    }

    public void setToStatus(OnboardingStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getReason() {
        return this.reason;
    }

    public OnboardingEvent reason(String reason) {
        this.reason = reason;
        return this;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getAt() {
        return this.at;
    }

    public OnboardingEvent at(Instant at) {
        this.at = at;
        return this;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OnboardingEvent)) {
            return false;
        }
        return getId() != null && getId().equals(((OnboardingEvent) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "OnboardingEvent{" +
                "id=" + getId() +
                ", applicationId='" + getApplicationId() + "'" +
                ", actor='" + getActor() + "'" +
                ", fromStatus='" + getFromStatus() + "'" +
                ", toStatus='" + getToStatus() + "'" +
                ", reason='" + getReason() + "'" +
                ", at='" + getAt() + "'" +
                "}";
    }
}
