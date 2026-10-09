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

    java.util.List<ProfessionalApplication> findByStatusOrderBySubmittedAtDesc(net.jojoaddison.domain.enumeration.ProfileStatus status);

    /**
     * Backs role broadcast in messaging: who currently holds a given clinical authority.
     * <p>
     * {@code authority} is what this service knows — the role string, named {@code requestedRole}
     * until T3 renamed it per {@code profile.md} § Gap Update. The authoritative grant lives in the
     * gateway, and for an ACTIVE application the two agree because the onboarding state machine
     * assigns the authority it was applied for (AUTHORITY_ASSIGNED). An authority changed directly
     * in the gateway, outside onboarding, would not be reflected here.
     * <p>
     * ⚠ <b>The method name is a derived query over the property name</b>, so it had to be renamed
     * with the field rather than kept for compatibility: Spring Data resolves
     * {@code findByRequestedRoleAndStatus} against {@code ProfessionalApplication} at context
     * startup and fails the whole {@code ApplicationContext} when the property is gone.
     */
    java.util.List<ProfessionalApplication> findByAuthorityAndStatus(
        String authority,
        net.jojoaddison.domain.enumeration.ProfileStatus status
    );
}
