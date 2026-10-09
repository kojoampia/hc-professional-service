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
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.Sex;
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
     * {@code authority}, {@code profileId}, {@code source} — none of which the contract reads.
     */
    static ProfessionalApplication consentedApplication(String accountId, ProfileStatus status) {
        return new ProfessionalApplication().accountId(accountId).status(status).agreed(true).agreedDate(Instant.now());
    }

    /**
     * Satisfies the {@code profile}, {@code address} and {@code nextOfKin} requirements <b>by the
     * stricter of the two definitions</b> — {@link net.jojoaddison.service.ProfileCompleteness}, all
     * 39 values — and therefore by the progress meter's three predicates as well.
     *
     * <h2>⭐ All 39 values since F-B, where it used to carry the meter's 14</h2>
     *
     * <p>It built {@code firstName}, {@code lastName}, {@code birthDate}, {@code sex},
     * {@code mobilePhone}, {@code cardType}, {@code cardNumber}, a four-field address and two
     * three-field contacts — exactly what {@code OnboardingService}'s advisory predicates read, and
     * no more. <b>F-B made step 4's submit gate read {@code ProfileCompleteness} instead</b>, because
     * {@code profile.md} conditions the move on <i>"all requirements are satisfied"</i> and the only
     * check on that path was step 3's documents. So this fixture now also carries
     * {@code middleNames}, {@code phoneNumber}, {@code email}, the address's
     * {@code digitalAddress}/{@code town}/{@code district}, and a full address plus an email on each
     * contact.
     *
     * <p>⚠ <b>The previous javadoc's warning no longer applies and is worth reading before it is
     * reinstated.</b> It said adding anything beyond the meter's reading would <i>"make the fixture
     * pass a stricter predicate than the service has, which is the direction that hides a
     * regression"</i>. That was right while the strictest gate in the service was the meter; the
     * service now <em>has</em> the stricter predicate and a gate that reads it, so a fixture stopping
     * at 14 values would make every submit walk 400 and the class's own subject unreachable. The
     * rule the warning was really about still holds: <b>this fixture must say exactly what "complete"
     * means to the server, and no more</b> — which is why it is one file, and why
     * {@code OnboardingProgressIT.gradesEachRequirementAsItIsSatisfied} feeds these very objects to
     * {@code GET /api/onboarding/progress} and demands 100%.
     *
     * <p><b>Two contacts, each complete</b> — {@code profile.md} step 2 requires at least two, and
     * {@code ProfileCompleteness.contactsProvided} requires <em>every</em> contact complete rather
     * than two complete ones among however many.
     */
    static Profile completeProfile(String accountId) {
        return new Profile()
            .accountId(accountId)
            .firstName("Appli")
            .middleNames("Nana Yaa")
            .lastName("Cant")
            .birthDate(LocalDate.of(1990, 1, 1))
            .sex(Sex.FEMALE)
            .mobilePhone("+233200000000")
            .phoneNumber("+233300000000")
            .email("appli.cant@example.com")
            .cardType(DocumentType.GHANACARD)
            .cardNumber("GHA-1")
            .address(completeAddress())
            // TWO contacts since F2, because profile.md step 2 requires at least two and
            // OnboardingService.nextOfKinComplete now counts them. One satisfied the old anyMatch,
            // so this fixture's whole point — "a profile the ACTIVE gate accepts" — stopped being
            // true the moment that predicate changed, and nothing but this file says so.
            //
            // Each one COMPLETE since F-B, address and email included: the submit gate reads
            // ProfileCompleteness, which requires every contact complete, not two complete ones.
            .contacts(List.of(completeContact("Ama", "Sister"), completeContact("Kofi", "Brother")));
    }

    /** All seven input fields of an {@link Address} — what {@code ProfileCompleteness} requires (F-B). */
    static Address completeAddress() {
        return new Address()
            .digitalAddress("GA-123-4567")
            .streetAddress("1 Road")
            .town("Osu")
            .city("Accra")
            .district("Ayawaso East")
            .region("Greater Accra")
            .country("Ghana");
    }

    /** One contact's eleven values: its four fields plus a full nested address (F-B). */
    static EmergencyContact completeContact(String name, String relationship) {
        return new EmergencyContact()
            .name(name)
            .relationship(relationship)
            .email(name.toLowerCase(Locale.ROOT) + "@example.com")
            .phone("+233200000001")
            .address(completeAddress());
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
