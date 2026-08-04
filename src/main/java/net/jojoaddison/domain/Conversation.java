package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A message thread.
 * <p>
 * Deliberately carries NO participant list. Membership is derived from
 * {@link MessageRecipient}, so the two cannot drift and JDL's lack of an array type never arises.
 * {@code createdBy} is a gateway account id, not a display name.
 */
@Document(collection = "conversation")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class Conversation implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("subject")
    private String subject;

    @Field("created_by")
    private String createdBy;

    @Field("created_at")
    private Instant createdAt;

    @Field("last_message_at")
    private Instant lastMessageAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Conversation id(String id) {
        this.id = id;
        return this;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Conversation subject(String subject) {
        this.subject = subject;
        return this;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Conversation createdBy(String createdBy) {
        this.createdBy = createdBy;
        return this;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Conversation createdAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(Instant lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public Conversation lastMessageAt(Instant lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Conversation other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Conversation{id='" + id + "'}";
    }
}
