package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

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

    /**
     * What survives a {@code PUT /api/profiles/{id}} now that the endpoint binds {@code ProfileDTO}.
     *
     * <p>Of the four WP2 fields, only the next of kin is carried by the DTO wire:
     * {@code specialtyCategoryId} and {@code teamIds} are not {@code ProfileDTO} fields and arrive as
     * unknown keys, and {@code title} is a DTO field {@code ProfileMapper.toEntity} never copies. A
     * {@code PUT} is a whole-document replace, so the three the wire cannot carry are <b>cleared</b>
     * by any PUT, however faithfully the caller echoes them.
     *
     * <p>The contact is sent in the DTO's vocabulary — {@code phoneNumber}, where the entity stores
     * {@code phone} — and asserted under both {@code contacts} and the {@code emergencyContact}
     * output alias, which still projects the first element on serialization.
     *
     * <p>{@code birthDate}, {@code sex}, {@code cardType} and {@code status} ride along because
     * {@code ProfileMapper.toEntity} parses all four unconditionally.
     */
    @Test
    @WithMockGatewayUser(authorities = { "ROLE_DOCTOR" })
    void aPutClearsTheWp2FieldsTheDtoWireCannotCarry() throws Exception {
        Profile profile = profileRepository.save(
            new Profile()
                .accountId("account-1")
                .firstName("Ama")
                .lastName("Serwaa")
                .title("RN")
                .specialtyCategoryId("cat-midwifery")
                .teamIds(List.of("team-1", "team-2"))
                .contacts(List.of(new EmergencyContact().name("Kojo A").relationship("spouse").phone("0242000000")))
        );

        String body =
            "{\"id\":\"" +
            profile.getId() +
            "\",\"firstName\":\"Ama\",\"lastName\":\"Serwaa\",\"title\":\"RN\"," +
            "\"specialtyCategoryId\":\"cat-midwifery\",\"teamIds\":[\"team-1\",\"team-2\"]," +
            "\"contacts\":[{\"name\":\"Kojo A\",\"relationship\":\"spouse\",\"phoneNumber\":\"0242000000\"}]," +
            "\"birthDate\":\"1990-01-01\",\"sex\":\"FEMALE\",\"cardType\":\"GHANACARD\",\"status\":\"ACTIVE\"}";

        restMockMvc
            .perform(put("/api/profiles/{id}", profile.getId()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpectAll(theNextOfKinUnderBothNames());

        // The independent read is an administrator's since backlog.md item 143: GET /api/profiles/**
        // takes its subject from the path and is ROLE_ADMIN. The write above stays the doctor's —
        // the mutation matrix is unchanged.
        restMockMvc
            .perform(get("/api/profiles/{id}", profile.getId()).with(gatewayUser("item143.admin", "ROLE_ADMIN")))
            .andExpect(status().isOk())
            .andExpectAll(theNextOfKinUnderBothNames());

        // Asserted on the stored row rather than the response, so the claim does not depend on how
        // the serializer renders a null.
        Profile after = profileRepository.findById(profile.getId()).orElseThrow();
        assertThat(after.getTitle()).isNull();
        assertThat(after.getSpecialtyCategoryId()).isNull();
        assertThat(after.getTeamIds()).isEmpty();
    }

    /**
     * The next of kin round-trips under BOTH names — {@code contacts} is the stored field,
     * {@code emergencyContact} the output alias projecting its first element. It is the only WP2
     * field the DTO wire still carries.
     */
    private static ResultMatcher[] theNextOfKinUnderBothNames() {
        return new ResultMatcher[] {
            jsonPath("$.contacts[0].name").value("Kojo A"),
            jsonPath("$.contacts[0].relationship").value("spouse"),
            jsonPath("$.contacts[0].phone").value("0242000000"),
            jsonPath("$.emergencyContact.name").value("Kojo A"),
            jsonPath("$.emergencyContact.relationship").value("spouse"),
            jsonPath("$.emergencyContact.phone").value("0242000000"),
        };
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
                .authority("ROLE_NURSE")
                .status(ProfileStatus.APPLICATION_STARTED)
                .agreed(true)
                .agreedDate(Instant.parse("2026-07-28T07:00:00Z"))
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
                .fromStatus(ProfileStatus.APPLICATION_STARTED)
                .toStatus(ProfileStatus.PROFILE_COMPLETED)
                .at(base.plus(1, ChronoUnit.HOURS))
        );
        onboardingEventRepository.save(
            new OnboardingEvent().applicationId("app-1").actor("system").toStatus(ProfileStatus.APPLICATION_STARTED).at(base)
        );
        onboardingEventRepository.save(new OnboardingEvent().applicationId("app-other").actor("x").at(base));

        List<OnboardingEvent> trail = onboardingEventRepository.findByApplicationIdOrderByAtAsc("app-1");
        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).getToStatus()).isEqualTo(ProfileStatus.APPLICATION_STARTED);
        assertThat(trail.get(1).getToStatus()).isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }
}
