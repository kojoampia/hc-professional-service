package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import net.jojoaddison.IntegrationTest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP3 gate (professional-onboarding-workflow.md § Status model): the full
 * legal onboarding path succeeds, every probed illegal transition is rejected
 * server-side with 409, guards (consent, mandatory documents, verified
 * documents, reviewer reason) hold, upload validation enforces the § Documents
 * rules, and the audit trail is appended per transition.
 */
@AutoConfigureMockMvc
@IntegrationTest
class OnboardingFlowIT {

    private static final String APPLICANT = "applicant1";
    private static final byte[] PDF_BYTES = "%PDF-1.4 minimal".getBytes(StandardCharsets.UTF_8);

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

    private Profile profile;

    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(new Profile().accountId(APPLICANT).firstName("Appli").lastName("Cant"));
    }

    @AfterEach
    void cleanup() {
        applicationRepository.deleteAll();
        eventRepository.deleteAll();
        profileRepository.deleteAll();
        personalDocumentRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void consentIsRequiredAndApplicationsAreUniquePerAccount() throws Exception {
        restMockMvc
            .perform(
                post("/api/onboarding/applications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestedRole\":\"ROLE_NURSE\",\"consentAccepted\":false}")
            )
            .andExpect(status().isBadRequest());

        startApplication();
        restMockMvc
            .perform(
                post("/api/onboarding/applications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestedRole\":\"ROLE_NURSE\",\"consentAccepted\":true}")
            )
            .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void uploadValidationEnforcesDocumentRules() throws Exception {
        startApplication();

        // wrong content type
        restMockMvc
            .perform(uploadFile(new MockMultipartFile("file", "x.txt", MediaType.TEXT_PLAIN_VALUE, PDF_BYTES), DocumentType.CERTIFICATE))
            .andExpect(status().isBadRequest());
        // declared pdf, wrong magic bytes
        restMockMvc
            .perform(
                uploadFile(
                    new MockMultipartFile("file", "x.pdf", MediaType.APPLICATION_PDF_VALUE, "not a pdf".getBytes()),
                    DocumentType.CERTIFICATE
                )
            )
            .andExpect(status().isBadRequest());
        // oversize
        byte[] big = new byte[5_000_001];
        big[0] = '%';
        big[1] = 'P';
        big[2] = 'D';
        big[3] = 'F';
        restMockMvc
            .perform(uploadFile(new MockMultipartFile("file", "big.pdf", MediaType.APPLICATION_PDF_VALUE, big), DocumentType.CERTIFICATE))
            .andExpect(status().isBadRequest());
        // OTHER without label
        restMockMvc
            .perform(uploadFile(new MockMultipartFile("file", "o.pdf", MediaType.APPLICATION_PDF_VALUE, PDF_BYTES), DocumentType.OTHER))
            .andExpect(status().isBadRequest());
        // license without expiry
        restMockMvc
            .perform(uploadFile(new MockMultipartFile("file", "l.pdf", MediaType.APPLICATION_PDF_VALUE, PDF_BYTES), DocumentType.LICENSE))
            .andExpect(status().isBadRequest());

        // valid upload: stored PENDING with checksum + size, bytes not echoed
        restMockMvc
            .perform(
                uploadFile(new MockMultipartFile("file", "cert.pdf", MediaType.APPLICATION_PDF_VALUE, PDF_BYTES), DocumentType.CERTIFICATE)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
            .andExpect(jsonPath("$.sizeBytes").value(PDF_BYTES.length))
            .andExpect(jsonPath("$.data").isEmpty());
        List<PersonalDocument> stored = personalDocumentRepository.findByProfileId(profile.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getSha256Checksum()).hasSize(64);
    }

    @Test
    @WithMockUser(username = "fresh-applicant", authorities = { "ROLE_USER" })
    void applicantUpsertsOwnProfileThroughOnboardingSurface() throws Exception {
        restMockMvc
            .perform(
                put("/api/onboarding/profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"firstName\":\"Fresh\",\"lastName\":\"Applicant\",\"accountId\":\"spoofed\",\"title\":\"RN\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("fresh-applicant"))
            .andExpect(jsonPath("$.title").value("RN"));
        // update keeps the same profile (no duplicate)
        restMockMvc
            .perform(
                put("/api/onboarding/profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"firstName\":\"Fresher\",\"lastName\":\"Applicant\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Fresher"));
        assertThat(profileRepository.findByAccountId("fresh-applicant")).isPresent();
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void applicantListsOwnDocumentsWithoutBytes() throws Exception {
        PersonalDocument doc = doc(DocumentType.CERTIFICATE, null);
        doc.setData("%PDF".getBytes());
        personalDocumentRepository.save(doc);
        restMockMvc
            .perform(get("/api/onboarding/documents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("CERTIFICATE"))
            .andExpect(jsonPath("$[0].data").isEmpty());
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void submitRequiresMandatoryDocumentSet() throws Exception {
        startApplication();
        restMockMvc.perform(put("/api/onboarding/applications/me/complete-profile")).andExpect(status().isOk());
        restMockMvc.perform(put("/api/onboarding/applications/me/submit")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void illegalTransitionsAreRejectedWithConflict() throws Exception {
        startApplication();
        // APPLICATION_STARTED -> CREDENTIAL_REVIEW without completing the profile
        seedMandatoryDocuments();
        restMockMvc.perform(put("/api/onboarding/applications/me/submit")).andExpect(status().isConflict());
    }

    @Test
    void reviewerEndpointsRequireAdmin() throws Exception {
        ProfessionalApplication application = applicationRepository.save(
            new ProfessionalApplication().accountId("someone").status(OnboardingStatus.CREDENTIAL_REVIEW)
        );
        restMockMvc
            .perform(
                put("/api/onboarding/applications/" + application.getId() + "/decide")
                    .with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                            "nurse"
                        ).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_NURSE"))
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"APPROVED\"}")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void fullLegalPathWithGuardsAndAuditTrail() throws Exception {
        // applicant part done directly through the repositories/service guards
        ProfessionalApplication application = applicationRepository.save(
            new ProfessionalApplication().accountId(APPLICANT).profileId(profile.getId()).status(OnboardingStatus.CREDENTIAL_REVIEW)
        );
        seedMandatoryDocuments();

        // approval blocked while documents are PENDING
        restMockMvc
            .perform(
                put("/api/onboarding/applications/" + application.getId() + "/decide")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"APPROVED\"}")
            )
            .andExpect(status().isConflict());

        // correction without reason is rejected
        restMockMvc
            .perform(
                put("/api/onboarding/applications/" + application.getId() + "/decide")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"RETURNED_FOR_CORRECTION\"}")
            )
            .andExpect(status().isBadRequest());

        // verify all documents, then approve and walk the activation chain
        personalDocumentRepository
            .findByProfileId(profile.getId())
            .forEach(d -> personalDocumentRepository.save(d.verificationStatus(VerificationStatus.VERIFIED)));

        decide(application.getId(), "APPROVED", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        restMockMvc
            .perform(
                put("/api/onboarding/applications/" + application.getId() + "/organization")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"specialtyCategoryId\":\"cat-1\",\"teamIds\":[\"team-1\"]}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ORGANIZATION_ASSIGNED"));
        restMockMvc.perform(put("/api/onboarding/applications/" + application.getId() + "/authority-assigned")).andExpect(status().isOk());
        restMockMvc.perform(put("/api/onboarding/applications/" + application.getId() + "/roster-configured")).andExpect(status().isOk());
        restMockMvc
            .perform(put("/api/onboarding/applications/" + application.getId() + "/activate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        // organization context landed on the profile
        Profile updated = profileRepository.findById(profile.getId()).orElseThrow();
        assertThat(updated.getSpecialtyCategoryId()).isEqualTo("cat-1");
        assertThat(updated.getTeamIds()).containsExactly("team-1");

        // skipping states is illegal: ACTIVE -> ORGANIZATION_ASSIGNED
        restMockMvc
            .perform(
                put("/api/onboarding/applications/" + application.getId() + "/organization")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"specialtyCategoryId\":\"cat-2\"}")
            )
            .andExpect(status().isConflict());

        // audit trail: one event per transition, chronological
        restMockMvc
            .perform(get("/api/onboarding/applications/" + application.getId() + "/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].toStatus").value("APPROVED"))
            .andExpect(jsonPath("$[4].toStatus").value("ACTIVE"));
        assertThat(eventRepository.findByApplicationIdOrderByAtAsc(application.getId())).hasSize(5);
    }

    private void startApplication() throws Exception {
        restMockMvc
            .perform(
                post("/api/onboarding/applications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestedRole\":\"ROLE_NURSE\",\"consentAccepted\":true}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("APPLICATION_STARTED"));
    }

    private void seedMandatoryDocuments() {
        personalDocumentRepository.save(doc(DocumentType.CERTIFICATE, null));
        personalDocumentRepository.save(doc(DocumentType.LICENSE, LocalDate.now().plusYears(1)));
        personalDocumentRepository.save(doc(DocumentType.GHANACARD, null));
        personalDocumentRepository.save(doc(DocumentType.PASSPHOTO, null));
    }

    private PersonalDocument doc(DocumentType type, LocalDate expiry) {
        return new PersonalDocument()
            .profileId(profile.getId())
            .name(type.name().toLowerCase() + ".pdf")
            .type(type)
            .expiryDate(expiry)
            .verificationStatus(VerificationStatus.PENDING);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder uploadFile(
        MockMultipartFile file,
        DocumentType type
    ) {
        var builder = multipart("/api/onboarding/documents").file(file).param("type", type.name());
        return builder;
    }

    private org.springframework.test.web.servlet.ResultActions decide(String id, String decision, String reason) throws Exception {
        String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return restMockMvc.perform(
            put("/api/onboarding/applications/" + id + "/decide")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\"" + reasonJson + "}")
        );
    }
}
