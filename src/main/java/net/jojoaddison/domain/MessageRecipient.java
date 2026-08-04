package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Per-recipient delivery and read state for a {@link Message}.
 * <p>
 * This collection is the source of truth for conversation membership, which is why
 * {@code conversationId} is denormalised onto it: "my conversations" is then a single lookup by
 * {@code recipientId} rather than a fan-out through {@link Message}.
 * <p>
 * A row is written for the SENDER too ({@code read = true}), otherwise a conversation someone only
 * ever sent into would vanish from their own list.
 */
@Document(collection = "messagerecipient")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class MessageRecipient implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("message_id")
    private String messageId;

    @Field("recipient_id")
    private String recipientId;

    @Field("conversation_id")
    private String conversationId;

    @Field("read")
    private Boolean read;

    @Field("read_at")
    private Instant readAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MessageRecipient id(String id) {
        this.id = id;
        return this;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public MessageRecipient messageId(String messageId) {
        this.messageId = messageId;
        return this;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }

    public MessageRecipient recipientId(String recipientId) {
        this.recipientId = recipientId;
        return this;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public MessageRecipient conversationId(String conversationId) {
        this.conversationId = conversationId;
        return this;
    }

    public Boolean getRead() {
        return read;
    }

    public void setRead(Boolean read) {
        this.read = read;
    }

    public MessageRecipient read(Boolean read) {
        this.read = read;
        return this;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public MessageRecipient readAt(Instant readAt) {
        this.readAt = readAt;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MessageRecipient other)) {
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
        return "MessageRecipient{id='" + id + "'}";
    }
}
