package net.jojoaddison.repository;

import java.util.Optional;
import net.jojoaddison.domain.ProfessionalApplication;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for the ProfessionalApplication entity.
 */
@Repository
public interface ProfessionalApplicationRepository extends MongoRepository<ProfessionalApplication, String> {
    Optional<ProfessionalApplication> findByAccountId(String accountId);

    java.util.List<ProfessionalApplication> findByStatusOrderBySubmittedAtDesc(net.jojoaddison.domain.enumeration.OnboardingStatus status);
}
