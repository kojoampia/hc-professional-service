package net.jojoaddison.repository;

import java.util.List;
import net.jojoaddison.domain.Metadata;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Metadata entity.
 */
@SuppressWarnings("unused")
@Repository
public interface MetadataRepository extends MongoRepository<Metadata, String> {
    List<Metadata> search(String query);
}
