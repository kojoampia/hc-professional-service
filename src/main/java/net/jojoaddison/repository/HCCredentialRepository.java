package net.jojoaddison.repository;

import java.util.List;
import net.jojoaddison.domain.HCCredential;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the HCCredential entity.
 */
@SuppressWarnings("unused")
@Repository
public interface HCCredentialRepository extends MongoRepository<HCCredential, String> {
    List<HCCredential> search(String query);
}
