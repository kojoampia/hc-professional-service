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

    Optional<ProfessionalApplication> findByProfileId(String profileId);

    java.util.List<ProfessionalApplication> findByStatusOrderBySubmittedAtDesc(net.jojoaddison.domain.enumeration.OnboardingStatus status);

    /**
     * Backs role broadcast in messaging: who currently holds a given clinical authority.
     * <p>
     * {@code requestedRole} is what this service knows. The authoritative grant lives in the
     * gateway, and for an ACTIVE application the two agree because the onboarding state machine
     * assigns the authority it was applied for (AUTHORITY_ASSIGNED). An authority changed directly
     * in the gateway, outside onboarding, would not be reflected here.
     */
    java.util.List<ProfessionalApplication> findByRequestedRoleAndStatus(
        String requestedRole,
        net.jojoaddison.domain.enumeration.OnboardingStatus status
    );
}
