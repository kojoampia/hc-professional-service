package net.jojoaddison.repository;

import net.jojoaddison.domain.HCDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the HCDocument entity.
 */
@SuppressWarnings("unused")
@Repository
public interface HCDocumentRepository extends MongoRepository<HCDocument, String> {}
