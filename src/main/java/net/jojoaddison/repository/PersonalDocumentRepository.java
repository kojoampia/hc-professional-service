package net.jojoaddison.repository;

import net.jojoaddison.domain.PersonalDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Document entity.
 */
@SuppressWarnings("unused")
@Repository
public interface PersonalDocumentRepository extends MongoRepository<PersonalDocument, String> {
    java.util.List<PersonalDocument> findByProfileId(String profileId);

    java.util.List<PersonalDocument> findByTypeAndExpiryDateLessThan(
        net.jojoaddison.domain.enumeration.DocumentType type,
        java.time.LocalDate date
    );
}
