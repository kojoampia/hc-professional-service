package net.jojoaddison.repository;

import java.util.Collection;
import java.util.List;
import net.jojoaddison.domain.Conversation;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for {@link Conversation}.
 */
@Repository
public interface ConversationRepository extends MongoRepository<Conversation, String> {
    /**
     * Loads the conversations the caller belongs to. The ids come from
     * {@code MessageRecipientRepository.findDistinctConversationIdsByRecipientId}, because
     * membership lives there rather than on the conversation.
     */
    List<Conversation> findByIdIn(Collection<String> ids, Sort sort);
}
