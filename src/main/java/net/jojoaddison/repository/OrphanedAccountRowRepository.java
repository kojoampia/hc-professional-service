package net.jojoaddison.repository;

import java.util.List;
import net.jojoaddison.domain.OrphanedAccountRow;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the {@link OrphanedAccountRow} document.
 */
@Repository
public interface OrphanedAccountRowRepository extends MongoRepository<OrphanedAccountRow, String> {
    /**
     * Whether this row has already been quarantined, so a re-run does not record it twice.
     *
     * @see net.jojoaddison.service.AccountIdMigrationService
     */
    List<OrphanedAccountRow> findByCollectionNameAndDocumentIdAndFieldName(String collectionName, String documentId, String fieldName);
}
