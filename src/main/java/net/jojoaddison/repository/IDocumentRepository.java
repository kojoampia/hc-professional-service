package net.jojoaddison.repository;

import net.jojoaddison.domain.IDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Document entity.
 */
@SuppressWarnings("unused")
@Repository
public interface IDocumentRepository extends MongoRepository<IDocument, String> {}
