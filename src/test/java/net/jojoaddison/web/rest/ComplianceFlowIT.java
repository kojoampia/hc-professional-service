package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.EmergencyContact;
import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.ComplianceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP7 gate (professional-onboarding-workflow.md § WP7): expiring license →
 * sweep restriction → reactivation blocked until a new verified license exists
 * → reactivation. Plus the funnel metrics (careers task 145) and the
 * admin-only fence around the compliance surface.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ComplianceFlowIT {

    private static final String PRO = "compliance-pro";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private OnboardingEventRepository eventRepository;

    private Profile profile;
    private ProfessionalApplication application;
    private PersonalDocument expiredLicense;

    /**
     * A fully onboarded ACTIVE nurse whose licence lapsed yesterday.
     *
     * <p><b>The fixture is deliberately complete, and that completeness is the fix for backlog.md
     * item 14 — a test that was red from 2026-08-20 to 2026-09-02.</b> It used to build a two-field
     * profile with a single document and then assert that reactivation returned 200. It returned 409,
     * and <em>the 409 was correct</em>: the production path was never wrong, the assertion had simply
     * outlived the behaviour it was written against.
     *
     * <p>What changed under it was {@code f8f579d7}, which added
     * {@code OnboardingService.requireCompleteProfile} so that a transition to ACTIVE requires the
     * eight-requirement completion contract as well as admin vetting. The old fixture satisfied exactly
     * one of the eight — the licence, ironically — so the response body was
     * {@code "Activation requires a complete profile; still missing: consent, profile, address,
     * nextOfKin, certificate, identity, photo"}. Seven names, and not one of them about licences.
     *
     * <p>That commit completed the two {@code OnboardingFlowIT} fixtures it broke and added
     * {@code OnboardingProgressIT}, which asserts this same activation path in both directions and has
     * agreed with the code ever since. It missed this class because its stated gate was
     * {@code Onboarding*IT} — a glob that does not match {@code ComplianceFlowIT}.
     *
     * <p>Completing the fixture is not a way round the gate, it is the state this test always meant to
     * describe: an application cannot <em>be</em> ACTIVE without having passed the completeness gate on
     * the way in, so an incomplete ACTIVE application is a state production cannot reach.
     */
    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(
            new Profile()
                .accountId(PRO)
                .firstName("Com")
                .lastName("Pliance")
                .birthDate(LocalDate.of(1990, 1, 1))
                .sex("female")
                .mobilePhone("+233200000000")
                .cardType("GHANACARD")
                .cardNumber("GHA-1")
                .address(new Address().streetAddress("1 Road").city("Accra").region("Greater Accra").country("Ghana"))
                .emergencyContact(new EmergencyContact().name("Ama").relationship("Sister").phone("+233200000001"))
        );
        application = applicationRepository.save(
            new ProfessionalApplication()
                .accountId(PRO)
                .login(PRO)
                .profileId(profile.getId())
                .requestedRole("ROLE_NURSE")
                .status(OnboardingStatus.ACTIVE)
                .source("web-careers")
                .consentAcceptedAt(Instant.now())
        );
        expiredLicense = personalDocumentRepository.save(
            mandatoryDocument(DocumentType.LICENSE, LocalDate.now().minusDays(1)).name("nursing-license.pdf")
        );
        // The other three mandatory documents. The lapsed licence above already satisfies the
        // `license` requirement — that one only asks for a LICENSE carrying an expiry date, expired or
        // not — so completeness holds throughout this class and licence *currency* is the only thing
        // that changes between the two reactivation attempts below.
        personalDocumentRepository.save(mandatoryDocument(DocumentType.CERTIFICATE, null).name("nursing-certificate.pdf"));
        personalDocumentRepository.save(mandatoryDocument(DocumentType.GHANACARD, null).name("ghana-card.pdf"));
        personalDocumentRepository.save(mandatoryDocument(DocumentType.PASSPHOTO, null).name("passport-photo.jpg"));
    }

    /** Verified because this professional is ACTIVE: nothing reaches that status un-vetted. */
    private PersonalDocument mandatoryDocument(DocumentType type, LocalDate expiryDate) {
        return new PersonalDocument()
            .profileId(profile.getId())
            .type(type)
            .expiryDate(expiryDate)
            .verificationStatus(VerificationStatus.VERIFIED);
    }

    @AfterEach
    void cleanup() {
        personalDocumentRepository.deleteAll();
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void expiredLicenseSweepRestrictsAndReactivationNeedsANewLicense() throws Exception {
        // the lapsed license shows up on the watchlist before the sweep
        restMockMvc
            .perform(get("/api/onboarding/compliance/expiring?days=30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].documentId").value(expiredLicense.getId()))
            .andExpect(jsonPath("$[0].login").value(PRO));

        // sweep suspends the ACTIVE application
        restMockMvc
            .perform(post("/api/onboarding/compliance/sweep"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiredLicenses").value(1))
            .andExpect(jsonPath("$.applicationsSuspended").value(1));
        assertThat(applicationRepository.findById(application.getId()).orElseThrow().getStatus()).isEqualTo(OnboardingStatus.SUSPENDED);
        assertThat(eventRepository.findByApplicationIdOrderByAtAsc(application.getId()))
            .extracting(OnboardingEvent::getReason)
            .anyMatch(reason -> reason != null && reason.startsWith(ComplianceService.LICENSE_EXPIRED_REASON));

        // re-running is idempotent: nothing left to suspend
        restMockMvc
            .perform(post("/api/onboarding/compliance/sweep"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applicationsSuspended").value(0));

        // Reactivation is blocked while the only license is expired. Before setUp() was completed this
        // assertion held for the wrong reason: the request 409'd on the completeness gate long before
        // it reached requireCurrentVerifiedLicense, so it would have passed with a perfectly current
        // licence too. It is the expired licence that has to be refusing it, so the body is asserted.
        restMockMvc
            .perform(put("/api/onboarding/applications/" + application.getId() + "/activate"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("Reactivation requires a verified, unexpired license")));

        // A new verified, unexpired license unlocks reactivation. This is the line that was red from
        // 2026-08-20 — see setUp(): 409 was the right answer to an incomplete profile, not a defect
        // in the reactivation path.
        personalDocumentRepository.save(
            new PersonalDocument()
                .name("nursing-license-renewed.pdf")
                .profileId(profile.getId())
                .type(DocumentType.LICENSE)
                .expiryDate(LocalDate.now().plusYears(1))
                .verificationStatus(VerificationStatus.VERIFIED)
        );
        restMockMvc
            .perform(put("/api/onboarding/applications/" + application.getId() + "/activate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        // full audit chain: suspension and reactivation are both events
        assertThat(eventRepository.findByApplicationIdOrderByAtAsc(application.getId()))
            .extracting(OnboardingEvent::getToStatus)
            .containsSubsequence(OnboardingStatus.SUSPENDED, OnboardingStatus.ACTIVE);
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void metricsCountFunnelByStatusAndSource() throws Exception {
        applicationRepository.save(new ProfessionalApplication().accountId("direct-1").status(OnboardingStatus.CREDENTIAL_REVIEW));
        restMockMvc
            .perform(get("/api/onboarding/compliance/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.byStatus.ACTIVE").value(1))
            .andExpect(jsonPath("$.byStatus.CREDENTIAL_REVIEW").value(1))
            .andExpect(jsonPath("$.bySource['web-careers']").value(1))
            .andExpect(jsonPath("$.bySource.direct").value(1))
            .andExpect(jsonPath("$.expiringLicenses30d").value(1));
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void recentEventsFeedIsNewestFirstAcrossApplications() throws Exception {
        restMockMvc.perform(post("/api/onboarding/compliance/sweep")).andExpect(status().isOk());
        restMockMvc
            .perform(get("/api/onboarding/compliance/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].applicationId").value(application.getId()))
            .andExpect(jsonPath("$[*].toStatus").value(hasItem("SUSPENDED")));
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void complianceSurfaceIsAdminOnly() throws Exception {
        restMockMvc.perform(post("/api/onboarding/compliance/sweep")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/onboarding/compliance/expiring")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/onboarding/compliance/metrics")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/onboarding/compliance/events")).andExpect(status().isForbidden());
    }
}
