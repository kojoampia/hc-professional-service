package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP5 gate (professional-onboarding-workflow.md): reviewer/admin affordances
 * are disjoint from applicant ones — listing, per-document verify/reject, and
 * cross-applicant document access are ROLE_ADMIN only, and the attribution
 * source is visible to reviewers.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ReviewerFlowIT {

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    private ProfessionalApplication application;
    private PersonalDocument document;

    @BeforeEach
    void setUp() {
        cleanup();
        Profile profile = profileRepository.save(new Profile().accountId("candidate").firstName("Can").lastName("Didate"));
        application = applicationRepository.save(
            new ProfessionalApplication()
                .accountId("candidate")
                .login("candidate")
                .profileId(profile.getId())
                .requestedRole("ROLE_NURSE")
                .status(OnboardingStatus.CREDENTIAL_REVIEW)
                .submittedAt(Instant.parse("2026-07-29T08:00:00Z"))
                .source("web-careers")
        );
        document = personalDocumentRepository.save(
            new PersonalDocument()
                .profileId(profile.getId())
                .name("license.pdf")
                .type(DocumentType.LICENSE)
                .verificationStatus(VerificationStatus.PENDING)
        );
    }

    @AfterEach
    void cleanup() {
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
        personalDocumentRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void adminListsApplicationsWithAttributionAndFilters() throws Exception {
        applicationRepository.save(new ProfessionalApplication().accountId("other").status(OnboardingStatus.APPLICATION_STARTED));
        restMockMvc.perform(get("/api/onboarding/applications")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        restMockMvc
            .perform(get("/api/onboarding/applications").param("status", "CREDENTIAL_REVIEW"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].source").value("web-careers"))
            .andExpect(jsonPath("$[0].requestedRole").value("ROLE_NURSE"));
    }

    @Test
    @WithMockUser(username = "nurse", authorities = { "ROLE_NURSE" })
    void listingIsAdminOnly() throws Exception {
        restMockMvc.perform(get("/api/onboarding/applications")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void adminReadsApplicantDocumentsWithoutBytes() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/applications/" + application.getId() + "/documents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("LICENSE"))
            .andExpect(jsonPath("$[0].data").isEmpty());
    }

    @Test
    @WithMockUser(username = "stranger", authorities = { "ROLE_USER" })
    void strangersCannotReadAnotherApplicantsDocuments() throws Exception {
        restMockMvc.perform(get("/api/onboarding/applications/" + application.getId() + "/documents")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void adminVerifiesAndRejectsDocumentsWithAudit() throws Exception {
        restMockMvc
            .perform(put("/api/onboarding/documents/" + document.getId() + "/verify"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
            .andExpect(jsonPath("$.verifiedBy").value("admin"));

        // rejection requires a reason
        restMockMvc
            .perform(put("/api/onboarding/documents/" + document.getId() + "/reject").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());

        restMockMvc
            .perform(
                put("/api/onboarding/documents/" + document.getId() + "/reject")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Expired license\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verificationStatus").value("REJECTED"))
            .andExpect(jsonPath("$.rejectionReason").value("Expired license"));
        assertThat(personalDocumentRepository.findById(document.getId()).orElseThrow().getVerifiedBy()).isEqualTo("admin");
    }

    @Test
    @WithMockUser(username = "candidate", authorities = { "ROLE_USER" })
    void applicantsCannotVerifyTheirOwnDocuments() throws Exception {
        restMockMvc.perform(put("/api/onboarding/documents/" + document.getId() + "/verify")).andExpect(status().isForbidden());
    }
}
