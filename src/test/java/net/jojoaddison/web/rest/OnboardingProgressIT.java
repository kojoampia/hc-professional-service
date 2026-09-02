package net.jojoaddison.web.rest;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.OnboardingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The completion contract (professional-onboarding-workflow.md § "Onboarding state events and the
 * completion contract"): eight equally weighted requirements computed by the server, and a
 * transition to {@code ACTIVE} that refuses an incomplete profile however it is reached.
 *
 * <p>Server-side is the whole point. A client-side percentage can read 100% while the service still
 * refuses to advance the application, so these assert the figure the {@code ACTIVE} gate and the
 * post-sign-in redirect both read.
 */
@AutoConfigureMockMvc
@IntegrationTest
class OnboardingProgressIT {

    private static final String APPLICANT = "progress-applicant";
    private static final String ADMIN = "progress-admin";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private OnboardingEventRepository eventRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void cleanup() {
        applicationRepository.deleteAll();
        eventRepository.deleteAll();
        profileRepository.deleteAll();
        personalDocumentRepository.deleteAll();
    }

    /**
     * An account created by admin invitation has a login before it has anything else. The profile
     * page has to render a meter for that person, so this answers rather than 404s.
     */
    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void reportsZeroForAnAccountWithNoApplicationAtAll() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.percent").value(0))
            .andExpect(jsonPath("$.complete").value(false))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.requirements.length()").value(8));
    }

    /**
     * `complete` and `status` answer different questions and the shell's first-login interstitial
     * depends on the difference: completeness is the applicant's half, while ACTIVE additionally
     * requires admin vetting. A finished profile nobody has reviewed is therefore complete and a
     * long way from ACTIVE, and anything gating on `complete` would treat it as live.
     */
    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void reportsTheApplicationStatusAlongsideCompleteness() throws Exception {
        ProfessionalApplication application = startedApplication();
        Profile saved = profileRepository.save(completeProfile());
        uploadAllMandatoryDocuments(saved);

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.status").value(application.getStatus().name()))
            .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.not("ACTIVE")));
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void gradesEachRequirementAsItIsSatisfied() throws Exception {
        ProfessionalApplication application = startedApplication();

        // Consent alone: 1 of 8 -> 13%.
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.percent").value(13));

        Profile saved = profileRepository.save(completeProfile());

        // Consent + personal details + address + next of kin: 4 of 8 -> 50%.
        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.percent").value(50))
            .andExpect(jsonPath("$.complete").value(false))
            .andExpect(jsonPath("$.requirements[?(@.key=='profile')].done").value(true))
            .andExpect(jsonPath("$.requirements[?(@.key=='nextOfKin')].done").value(true))
            .andExpect(jsonPath("$.requirements[?(@.key=='certificate')].done").value(false));

        uploadAllMandatoryDocuments(saved);

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.percent").value(100))
            .andExpect(jsonPath("$.complete").value(true));
    }

    /** A licence without an expiry date does not count — the compliance sweep has nothing to sweep. */
    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void doesNotCreditALicenceWithNoExpiryDate() throws Exception {
        startedApplication();
        Profile saved = profileRepository.save(completeProfile());
        personalDocumentRepository.save(document(saved, DocumentType.LICENSE, null));

        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.requirements[?(@.key=='license')].done").value(false));

        personalDocumentRepository.save(document(saved, DocumentType.LICENSE, LocalDate.now().plusYears(1)));

        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.requirements[?(@.key=='license')].done").value(true));
    }

    /**
     * The half of "active only when complete AND vetted" that the admin cannot override. Vetting is
     * the APPROVED -> ... -> ROSTER_CONFIGURED chain this fixture starts from; completeness is
     * checked here, so an admin activating an unfinished application gets a 409 naming what is
     * missing rather than a working account.
     */
    @Test
    @WithMockUser(username = ADMIN, authorities = { "ROLE_ADMIN" })
    void refusesToActivateAnIncompleteProfile() throws Exception {
        ProfessionalApplication application = applicationRepository.save(
            new ProfessionalApplication()
                .accountId(APPLICANT)
                .login(APPLICANT)
                .requestedRole("ROLE_NURSE")
                .status(OnboardingStatus.ROSTER_CONFIGURED)
                .consentAcceptedAt(java.time.Instant.now())
        );

        // The body is asserted, not just the status. A bare 409 outlives the reason it was written for:
        // any of the other refusals on this path — an illegal transition, a missing profile — returns
        // the same code, so a status-only assertion would keep passing while this test stopped being
        // about completeness at all. That is how backlog item 14 started.
        restMockMvc
            .perform(put("/api/onboarding/applications/" + application.getId() + "/activate"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString(OnboardingService.ACTIVATION_REQUIRES_COMPLETE_PROFILE)));
    }

    @Test
    @WithMockUser(username = ADMIN, authorities = { "ROLE_ADMIN" })
    void activatesOnceEveryRequirementIsSatisfied() throws Exception {
        ProfessionalApplication application = applicationRepository.save(
            new ProfessionalApplication()
                .accountId(APPLICANT)
                .login(APPLICANT)
                .requestedRole("ROLE_NURSE")
                .status(OnboardingStatus.ROSTER_CONFIGURED)
                .consentAcceptedAt(java.time.Instant.now())
        );
        uploadAllMandatoryDocuments(profileRepository.save(completeProfile()));

        restMockMvc
            .perform(put("/api/onboarding/applications/" + application.getId() + "/activate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(OnboardingStatus.ACTIVE.name()));
    }

    private ProfessionalApplication startedApplication() {
        return applicationRepository.save(
            new ProfessionalApplication()
                .accountId(APPLICANT)
                .login(APPLICANT)
                .requestedRole("ROLE_NURSE")
                .status(OnboardingStatus.APPLICATION_STARTED)
                .consentAcceptedAt(java.time.Instant.now())
        );
    }

    private Profile completeProfile() {
        return new Profile()
            .accountId(APPLICANT)
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

    private void uploadAllMandatoryDocuments(Profile profile) {
        personalDocumentRepository.save(document(profile, DocumentType.CERTIFICATE, null));
        personalDocumentRepository.save(document(profile, DocumentType.LICENSE, LocalDate.now().plusYears(1)));
        personalDocumentRepository.save(document(profile, DocumentType.GHANACARD, null));
        personalDocumentRepository.save(document(profile, DocumentType.PASSPHOTO, null));
    }

    private PersonalDocument document(Profile profile, DocumentType type, LocalDate expiry) {
        return new PersonalDocument().profileId(profile.getId()).type(type).expiryDate(expiry);
    }
}
