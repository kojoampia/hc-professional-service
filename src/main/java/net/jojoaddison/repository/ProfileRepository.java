package net.jojoaddison.repository;

import java.util.Optional;
import net.jojoaddison.domain.Profile;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the Profile entity.
 */
@SuppressWarnings("unused")
@Repository
public interface ProfileRepository extends MongoRepository<Profile, String> {
    Optional<Profile> findByAccountId(String accountId);
    Optional<Profile> findByEmail(String email);
}
