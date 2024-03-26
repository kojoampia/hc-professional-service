package net.jojoaddison.repository;

import net.jojoaddison.domain.HCDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Document entity.
 */
@SuppressWarnings("unused")
@Repository
public interface DocumentRepository extends MongoRepository<HCDocument, String> {}
