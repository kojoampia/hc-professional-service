package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import net.jojoaddison.IntegrationTest;
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

    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(new Profile().accountId(PRO).firstName("Com").lastName("Pliance"));
        application = applicationRepository.save(
            new ProfessionalApplication()
                .accountId(PRO)
                .login(PRO)
                .profileId(profile.getId())
                .requestedRole("ROLE_NURSE")
                .status(OnboardingStatus.ACTIVE)
                .source("web-careers")
        );
        expiredLicense = personalDocumentRepository.save(
            new PersonalDocument()
                .name("nursing-license.pdf")
                .profileId(profile.getId())
                .type(DocumentType.LICENSE)
                .expiryDate(LocalDate.now().minusDays(1))
                .verificationStatus(VerificationStatus.VERIFIED)
        );
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

        // reactivation is blocked while the only license is expired
        restMockMvc.perform(put("/api/onboarding/applications/" + application.getId() + "/activate")).andExpect(status().isConflict());

        // a new verified, unexpired license unlocks reactivation
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
