package net.jojoaddison.repository;

import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.MessageRecipient;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for {@link MessageRecipient}.
 * <p>
 * This collection answers "what can this account see", so every finder here is scoped by
 * {@code recipientId}. Reads elsewhere must go through one of these rather than loading a
 * {@link net.jojoaddison.domain.Message} by id directly, or the authorization boundary is lost.
 */
@Repository
public interface MessageRecipientRepository extends MongoRepository<MessageRecipient, String> {
    List<MessageRecipient> findByRecipientIdOrderByIdDesc(String recipientId);

    List<MessageRecipient> findByRecipientIdAndConversationId(String recipientId, String conversationId);

    Optional<MessageRecipient> findByMessageIdAndRecipientId(String messageId, String recipientId);

    List<MessageRecipient> findByMessageId(String messageId);

    long countByRecipientIdAndReadIsFalse(String recipientId);

    List<MessageRecipient> findByRecipientIdAndReadIsFalse(String recipientId);
}
