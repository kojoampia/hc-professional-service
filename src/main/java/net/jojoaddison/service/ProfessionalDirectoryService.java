package net.jojoaddison.service;

import java.util.Optional;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.dto.DirectoryRecordDTO;
import org.springframework.stereotype.Service;

/**
 * Composes the one narrow read a sibling product needs to turn an {@code accountId} into a person
 * (backlog.md item 51).
 *
 * <p>Read-only, and deliberately so: nothing here writes, announces or transitions anything. It
 * joins {@code Profile}, {@code ProfessionalApplication} and the credential rows and stops.
 *
 * @see DirectoryRecordDTO for the contract, the role vocabulary, and why no licence number is
 *      carried.
 */
@Service
public class ProfessionalDirectoryService {

    private final ProfileRepository profileRepository;

    private final ProfessionalApplicationRepository applicationRepository;

    private final PersonalDocumentRepository personalDocumentRepository;

    /**
     * Injected for one call, {@link OnboardingService#hasCurrentVerifiedLicense(String)}.
     *
     * <p>That predicate — live, {@code LICENSE}, {@code VERIFIED}, dated and not past its date — is
     * what this service's transition to {@code ACTIVE} and its reactivation out of {@code
     * SUSPENDED} both read. Restating it here would be a second definition of "holds a current
     * licence" that could drift from the one the gates enforce, and a directory that disagreed with
     * the activation gate would be wrong in the direction nobody checks: a record that reads
     * plausibly.
     */
    private final OnboardingService onboardingService;

    public ProfessionalDirectoryService(
        ProfileRepository profileRepository,
        ProfessionalApplicationRepository applicationRepository,
        PersonalDocumentRepository personalDocumentRepository,
        OnboardingService onboardingService
    ) {
        this.profileRepository = profileRepository;
        this.applicationRepository = applicationRepository;
        this.personalDocumentRepository = personalDocumentRepository;
        this.onboardingService = onboardingService;
    }

    /**
     * The directory record for an account, or {@link Optional#empty()} if this service has never
     * heard of it.
     *
     * <p><b>Either row is enough for the account to exist here, and that is a decision rather than
     * a convenience.</b> The two rows arrive in either order — an applicant registers and files an
     * application before completing a profile, while a clinician created by admin invitation gets a
     * profile first — so anchoring on {@code Profile} alone would answer "no such clinician" for
     * somebody this service is actively onboarding, at exactly the moment the consumer is being
     * told by an event that they exist. Empty here means neither row is present, which is the only
     * state that honestly reads as unknown.
     *
     * <p><b>Every lookup is by {@code accountId}, which is the gateway's {@code User.id}</b>
     * (backlog.md item 50). There is no fallback to a login: the three products share a signing key
     * and not a user store, so a login is a second identifier in a second space, and matching on it
     * is the join that item removed.
     */
    public Optional<DirectoryRecordDTO> directoryRecord(String accountId) {
        Optional<Profile> profile = profileRepository.findByAccountId(accountId);
        Optional<ProfessionalApplication> application = applicationRepository.findByAccountId(accountId);
        if (profile.isEmpty() && application.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            new DirectoryRecordDTO(
                accountId,
                profile.map(Profile::getFirstName).orElse(null),
                profile.map(Profile::getLastName).orElse(null),
                application.map(ProfessionalApplication::getRequestedRole).orElse(null),
                application.map(ProfessionalApplication::getStatus).orElse(null),
                profile.map(Profile::getId).map(this::licenceVerified).orElse(null)
            )
        );
    }

    /**
     * Whether this clinician holds a current verified licence, or {@code null} when they hold no
     * licence at all.
     *
     * <p><b>Two questions, not one</b>, and collapsing them is the failure this null exists to
     * avoid. "No licence has ever been uploaded" and "a licence is on file and is not currently
     * valid" both answer {@code false} to the gate, and they need different people chasing: one an
     * applicant, the other a reviewer or a renewal.
     *
     * <p><b>A superseded row is not a licence on file.</b> {@code PersonalDocumentService.isLive}
     * is the single definition of that, shared with the compliance sweep — superseding is a marker
     * and never a delete, so an archived credential stays readable as evidence of what a clinician
     * held while treating patients while every "what do they hold now" reader steps over it
     * (backlog.md item 20). Absent means current, because rows written before the field existed
     * carry no value at all and MongoDB's equality-to-null matches a missing field.
     *
     * <p>The collection is read here for presence and read again inside {@code
     * hasCurrentVerifiedLicense} for the verdict. That is a deliberate trade: one more read of a
     * handful of rows, against a second copy of the currency rule in a second place. The rule is
     * the expensive thing to get wrong.
     */
    private Boolean licenceVerified(String profileId) {
        boolean holdsALicence = personalDocumentRepository
            .findByProfileId(profileId)
            .stream()
            .filter(PersonalDocumentService::isLive)
            .map(PersonalDocument::getType)
            .anyMatch(DocumentType.LICENSE::equals);
        return holdsALicence ? onboardingService.hasCurrentVerifiedLicense(profileId) : null;
    }
}
