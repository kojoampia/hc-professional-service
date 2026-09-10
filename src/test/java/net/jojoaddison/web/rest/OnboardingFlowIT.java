package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
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
import net.jojoaddison.domain.Category;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Team;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.CategoryRepository;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TeamRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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

    // The organisation step below names a category and two teams, and since backlog.md item 60 a
    // write naming either has to resolve. These rows exist so that this class goes on testing what
    // it is about — the transition chain and its guards — rather than the new refusal, which
    // OrganizationReferenceIntegrityIT owns.
    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    private Profile profile;

    @BeforeEach
    void setUp() {
        cleanup();
        // Complete, not minimal: since the completion contract landed, the transition to ACTIVE
        // refuses a profile that is missing any of the eight requirements (see OnboardingProgressIT).
        // This fixture exists to exercise the transition chain, so it has to clear that gate — the
        // gate itself is asserted there rather than here, and its shape is CompleteOnboardingFixture's
        // rather than this class's, so a ninth requirement lands on all three at once (item 18).
        profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));
    }

    @AfterEach
    void cleanup() {
        applicationRepository.deleteAll();
        eventRepository.deleteAll();
        profileRepository.deleteAll();
        personalDocumentRepository.deleteAll();
        categoryRepository.deleteAll();
        teamRepository.deleteAll();
    }

    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
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
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
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
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void attributionSourcePersistsTruncatedAndOptional() throws Exception {
        String longSource = "web-careers-" + "x".repeat(100);
        restMockMvc
            .perform(
                post("/api/onboarding/applications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestedRole\":\"ROLE_NURSE\",\"consentAccepted\":true,\"source\":\"" + longSource + "\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.source").value(longSource.substring(0, 64)));

        // absent source stays null (graceful degradation for direct visitors)
        applicationRepository.deleteAll();
        startApplication();
        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getSource()).isNull();
    }

    @Test
    @WithMockGatewayUser(login = "fresh-applicant", authorities = { "ROLE_USER" })
    void applicantUpsertsOwnProfileThroughOnboardingSurface() throws Exception {
        restMockMvc
            .perform(
                put("/api/onboarding/profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"firstName\":\"Fresh\",\"lastName\":\"Applicant\",\"accountId\":\"spoofed\",\"title\":\"RN\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(accountIdFor("fresh-applicant")))
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
        assertThat(profileRepository.findByAccountId(accountIdFor("fresh-applicant"))).isPresent();
    }

    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
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
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void submitRequiresMandatoryDocumentSet() throws Exception {
        startApplication();
        restMockMvc.perform(put("/api/onboarding/applications/me/complete-profile")).andExpect(status().isOk());
        restMockMvc.perform(put("/api/onboarding/applications/me/submit")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
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
                    .with(gatewayUser("nurse", "ROLE_NURSE"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"APPROVED\"}")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void fullLegalPathWithGuardsAndAuditTrail() throws Exception {
        // Applicant part done directly through the repositories/service guards. consentAcceptedAt comes
        // stamped from the fixture because this test skips the applicant steps that would normally set
        // it, and the transition to ACTIVE counts consent among the eight completion requirements.
        ProfessionalApplication application = applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(accountIdFor(APPLICANT), OnboardingStatus.CREDENTIAL_REVIEW).profileId(
                profile.getId()
            )
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
        categoryRepository.save(new Category().id("cat-1").name("Geriatric nursing"));
        categoryRepository.save(new Category().id("cat-2").name("Palliative care"));
        teamRepository.save(new Team().id("team-1").name("Home visits \u00b7 North"));
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
        personalDocumentRepository.saveAll(CompleteOnboardingFixture.mandatoryDocuments(profile));
    }

    private PersonalDocument doc(DocumentType type, LocalDate expiry) {
        return CompleteOnboardingFixture.document(profile, type, expiry);
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
