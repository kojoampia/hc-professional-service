package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One message in a {@link Conversation}.
 * <p>
 * {@code recipientRole} records the INTENT of a role broadcast (e.g. {@code ROLE_NURSE}) and is
 * null for an explicitly addressed message. It is not what delivers the message: the service
 * expands the role into {@link MessageRecipient} rows at send time, so read state exists per person
 * and the record of who it was aimed at survives later staffing changes. Someone who joins that
 * role afterwards does not receive it retroactively.
 * <p>
 * {@code senderId} is a gateway account id; {@code senderName} is denormalised for display, the
 * same compromise {@code ProfessionalApplication.login} makes.
 */
@Document(collection = "message")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    @Field("conversation_id")
    private String conversationId;

    @Field("sender_id")
    private String senderId;

    @Field("sender_name")
    private String senderName;

    @Field("body")
    private String body;

    @Field("sent_at")
    private Instant sentAt;

    @Field("recipient_role")
    private String recipientRole;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Message id(String id) {
        this.id = id;
        return this;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Message conversationId(String conversationId) {
        this.conversationId = conversationId;
        return this;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public Message senderId(String senderId) {
        this.senderId = senderId;
        return this;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public Message senderName(String senderName) {
        this.senderName = senderName;
        return this;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Message body(String body) {
        this.body = body;
        return this;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Message sentAt(Instant sentAt) {
        this.sentAt = sentAt;
        return this;
    }

    public String getRecipientRole() {
        return recipientRole;
    }

    public void setRecipientRole(String recipientRole) {
        this.recipientRole = recipientRole;
    }

    public Message recipientRole(String recipientRole) {
        this.recipientRole = recipientRole;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message other)) {
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
        return "Message{id='" + id + "'}";
    }
}
