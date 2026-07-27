package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.jojoaddison.IntegrationTest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP2 gate (professional-onboarding-workflow.md § Data contracts): coverage
 * for every new/changed contract — Profile extensions + unique accountId,
 * PersonalDocument verification metadata, normalized Team members (covered by
 * the updated TeamResourceIT), and the ProfessionalApplication /
 * OnboardingEvent collections.
 */
@AutoConfigureMockMvc
@IntegrationTest
class OnboardingContractsIT {

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private ProfessionalApplicationRepository professionalApplicationRepository;

    @Autowired
    private OnboardingEventRepository onboardingEventRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void ensureIndexes() {
        // The startup ApplicationRunner does this in the real app; tests hit the template directly.
        mongoTemplate.indexOps(Profile.class).createIndex(new Index("account_id", Sort.Direction.ASC).unique().sparse());
        mongoTemplate.indexOps(ProfessionalApplication.class).createIndex(new Index("account_id", Sort.Direction.ASC).unique().sparse());
    }

    @AfterEach
    void cleanup() {
        profileRepository.deleteAll();
        personalDocumentRepository.deleteAll();
        professionalApplicationRepository.deleteAll();
        onboardingEventRepository.deleteAll();
    }

    @Test
    @WithMockUser(authorities = { "ROLE_DOCTOR" })
    void profileRoundTripsOnboardingFieldsOverRest() throws Exception {
        Profile profile = new Profile()
            .accountId("account-1")
            .firstName("Ama")
            .lastName("Serwaa")
            .title("RN")
            .specialtyCategoryId("cat-midwifery")
            .teamIds(List.of("team-1", "team-2"))
            .emergencyContact(new EmergencyContact().name("Kojo A").relationship("spouse").phone("0242000000"));

        restMockMvc
            .perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(profile)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("RN"))
            .andExpect(jsonPath("$.specialtyCategoryId").value("cat-midwifery"))
            .andExpect(jsonPath("$.teamIds", org.hamcrest.Matchers.contains("team-1", "team-2")))
            .andExpect(jsonPath("$.emergencyContact.name").value("Kojo A"))
            .andExpect(jsonPath("$.emergencyContact.relationship").value("spouse"))
            .andExpect(jsonPath("$.emergencyContact.phone").value("0242000000"));
    }

    @Test
    void accountIdIsUniquePerProfile() {
        profileRepository.save(new Profile().accountId("account-unique").firstName("First"));
        assertThatThrownBy(() -> profileRepository.save(new Profile().accountId("account-unique").firstName("Second"))).isInstanceOf(
            DuplicateKeyException.class
        );
    }

    @Test
    void personalDocumentCarriesVerificationMetadata() {
        PersonalDocument saved = personalDocumentRepository.save(
            new PersonalDocument()
                .name("license.pdf")
                .profileId("profile-1")
                .type(DocumentType.OTHER)
                .otherLabel("Specialist accreditation")
                .sha256Checksum("ab12")
                .sizeBytes(2048L)
                .expiryDate(LocalDate.of(2027, 1, 31))
                .verificationStatus(VerificationStatus.PENDING)
        );

        PersonalDocument reloaded = personalDocumentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getOtherLabel()).isEqualTo("Specialist accreditation");
        assertThat(reloaded.getSha256Checksum()).isEqualTo("ab12");
        assertThat(reloaded.getSizeBytes()).isEqualTo(2048L);
        assertThat(reloaded.getExpiryDate()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(reloaded.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);

        reloaded.verificationStatus(VerificationStatus.VERIFIED).verifiedBy("reviewer-1").verifiedAt(Instant.parse("2026-07-28T08:00:00Z"));
        personalDocumentRepository.save(reloaded);
        assertThat(personalDocumentRepository.findById(saved.getId()).orElseThrow().getVerifiedBy()).isEqualTo("reviewer-1");
    }

    @Test
    void professionalApplicationIsUniquePerAccountAndFindable() {
        ProfessionalApplication application = professionalApplicationRepository.save(
            new ProfessionalApplication()
                .accountId("account-2")
                .login("ama.serwaa")
                .requestedRole("ROLE_NURSE")
                .status(OnboardingStatus.APPLICATION_STARTED)
                .consentAcceptedAt(Instant.parse("2026-07-28T07:00:00Z"))
        );

        assertThat(professionalApplicationRepository.findByAccountId("account-2")).contains(application);
        assertThatThrownBy(
            () -> professionalApplicationRepository.save(new ProfessionalApplication().accountId("account-2").login("someone.else"))
        ).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void onboardingEventsReadBackInChronologicalOrder() {
        Instant base = Instant.parse("2026-07-28T07:00:00Z");
        onboardingEventRepository.save(
            new OnboardingEvent()
                .applicationId("app-1")
                .actor("ama.serwaa")
                .fromStatus(OnboardingStatus.APPLICATION_STARTED)
                .toStatus(OnboardingStatus.PROFILE_COMPLETED)
                .at(base.plus(1, ChronoUnit.HOURS))
        );
        onboardingEventRepository.save(
            new OnboardingEvent().applicationId("app-1").actor("system").toStatus(OnboardingStatus.APPLICATION_STARTED).at(base)
        );
        onboardingEventRepository.save(new OnboardingEvent().applicationId("app-other").actor("x").at(base));

        List<OnboardingEvent> trail = onboardingEventRepository.findByApplicationIdOrderByAtAsc("app-1");
        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).getToStatus()).isEqualTo(OnboardingStatus.APPLICATION_STARTED);
        assertThat(trail.get(1).getToStatus()).isEqualTo(OnboardingStatus.PROFILE_COMPLETED);
    }
}
