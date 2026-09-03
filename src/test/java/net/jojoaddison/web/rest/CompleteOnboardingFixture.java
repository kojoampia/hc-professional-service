package net.jojoaddison.web.rest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;

/**
 * A fully onboarded professional, built once for every integration test that needs one
 * (professional-onboarding-workflow.md § "Onboarding state events and the completion contract").
 *
 * <p><b>Why this exists (backlog.md item 18).</b> The eight-requirement completion contract was built
 * three different ways in three IT classes — {@code ComplianceFlowIT}, {@code OnboardingProgressIT}
 * and {@code OnboardingFlowIT} — and no two were the same code. It is the thing in this domain most
 * likely to gain a requirement, and when it does the failure lands as a red test in a class whose
 * subject is something else: {@code f8f579d7} added {@code OnboardingService.requireCompleteProfile},
 * completed the two {@code OnboardingFlowIT} fixtures it broke, and left {@code ComplianceFlowIT} red
 * for thirteen days. <b>A ninth requirement is now one edit, here</b> — in
 * {@link #consentedApplication} if it lives on the application, {@link #completeProfile} if it lives
 * on the profile, {@link #mandatoryDocuments} if it is a document.
 *
 * <p><b>What it does not do is decide what the contract is.</b> The eight requirements are computed by
 * {@code OnboardingService.progressFor} from private predicates that no fixture can enumerate, so this
 * is a transcription and could drift from the service in principle. What stops it is that the
 * transcription is asserted against the server's own computation:
 * {@code OnboardingProgressIT.gradesEachRequirementAsItIsSatisfied} feeds exactly these objects to
 * {@code GET /api/onboarding/progress} and requires 100% and {@code complete: true}. Adding a ninth
 * requirement therefore fails there first, and in all three classes at once, rather than in whichever
 * one happened to be run.
 *
 * <p>Builders, not writes: every method returns an unsaved document so the calling class keeps control
 * of its own repositories, its own extra fields (login, requested role, attribution source, profile
 * id) and its own choice of what to leave out — {@code OnboardingProgressIT} deliberately builds
 * <em>incomplete</em> states, which is only possible if applying each piece is the caller's decision.
 */
final class CompleteOnboardingFixture {

    private CompleteOnboardingFixture() {}

    /**
     * A licence expiry comfortably in the future — the ordinary case, where currency is not the
     * subject. {@code ComplianceFlowIT} passes a past date instead; that is its one variation.
     */
    static LocalDate currentLicenseExpiry() {
        return LocalDate.now().plusYears(1);
    }

    /**
     * Satisfies the {@code consent} requirement: an application in {@code status} whose consent is
     * stamped. Callers chain whatever else their own subject needs — {@code login},
     * {@code requestedRole}, {@code profileId}, {@code source} — none of which the contract reads.
     */
    static ProfessionalApplication consentedApplication(String accountId, OnboardingStatus status) {
        return new ProfessionalApplication().accountId(accountId).status(status).consentAcceptedAt(Instant.now());
    }

    /**
     * Satisfies the {@code profile}, {@code address} and {@code nextOfKin} requirements: every field
     * {@code personalDetailsComplete}, {@code addressComplete} and {@code nextOfKinComplete} read.
     */
    static Profile completeProfile(String accountId) {
        return new Profile()
            .accountId(accountId)
            .firstName("Appli")
            .lastName("Cant")
            .birthDate(LocalDate.of(1990, 1, 1))
            .sex("female")
            .mobilePhone("+233200000000")
            .cardType("GHANACARD")
            .cardNumber("GHA-1")
            .address(new Address().streetAddress("1 Road").city("Accra").region("Greater Accra").country("Ghana"))
            .emergencyContact(new EmergencyContact().name("Ama").relationship("Sister").phone("+233200000001"));
    }

    /**
     * The four documents that satisfy the {@code certificate}, {@code license}, {@code identity} and
     * {@code photo} requirements, as freshly uploaded: a current licence, everything {@code PENDING}.
     */
    static List<PersonalDocument> mandatoryDocuments(Profile profile) {
        return mandatoryDocuments(profile, currentLicenseExpiry(), VerificationStatus.PENDING);
    }

    /**
     * The same four documents with the two axes a class may legitimately need to vary: the licence
     * expiry, because {@code ComplianceFlowIT}'s subject is the licence guard and it holds a lapsed
     * one beside an otherwise complete set; and the verification status, because a professional who
     * is already {@code ACTIVE} cannot hold unvetted documents while an applicant under review must.
     *
     * <p>Neither axis moves the {@code license} requirement itself, which asks only for a LICENSE
     * carrying an expiry date — expired or not, verified or not. Currency is checked separately, by
     * the reactivation guard and the compliance sweep.
     */
    static List<PersonalDocument> mandatoryDocuments(Profile profile, LocalDate licenseExpiry, VerificationStatus verificationStatus) {
        return List.of(
            document(profile, DocumentType.CERTIFICATE, null, verificationStatus),
            document(profile, DocumentType.LICENSE, licenseExpiry, verificationStatus),
            document(profile, DocumentType.GHANACARD, null, verificationStatus),
            document(profile, DocumentType.PASSPHOTO, null, verificationStatus)
        );
    }

    /** One document, as freshly uploaded. */
    static PersonalDocument document(Profile profile, DocumentType type, LocalDate expiryDate, VerificationStatus verificationStatus) {
        return new PersonalDocument()
            .profileId(profile.getId())
            .name(type.name().toLowerCase(Locale.ROOT) + ".pdf")
            .type(type)
            .expiryDate(expiryDate)
            .verificationStatus(verificationStatus);
    }

    /** One document at the default verification status, for the cases that vary only the type. */
    static PersonalDocument document(Profile profile, DocumentType type, LocalDate expiryDate) {
        return document(profile, type, expiryDate, VerificationStatus.PENDING);
    }
}
