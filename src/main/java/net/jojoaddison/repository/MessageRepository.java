package net.jojoaddison.repository;

import java.util.List;
import net.jojoaddison.domain.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for {@link Message}.
 */
@Repository
public interface MessageRepository extends MongoRepository<Message, String> {
    Page<Message> findByConversationIdOrderBySentAtAsc(String conversationId, Pageable pageable);

    List<Message> findByIdInOrderBySentAtDesc(List<String> ids);
}
