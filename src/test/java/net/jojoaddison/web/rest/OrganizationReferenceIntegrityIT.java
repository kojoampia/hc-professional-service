package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Category;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Team;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.repository.CategoryRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TeamRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.OnboardingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * A write that names a category or a team is refused unless the row exists (backlog.md item 60,
 * second half).
 *
 * <p><b>Where this sits.</b> {@code ReferenceDataDeletionIT} (item 57) closed the delete side —
 * nothing a profile points at can be removed out from under it — and its own javadoc named this as
 * the gap it left: "neither {@code OnboardingService.assignOrganization} nor {@code ProfileResource}'s
 * whole-entity write checks that the ids they store name existing rows, so a typo still produces
 * exactly the dangling pointer this class refuses to let a delete produce." Verified live on the
 * quality stack on 2026-09-09: a {@code PUT /api/profiles/&#123;id&#125;} carrying
 * {@code specialtyCategoryId: "does-not-exist-cat"} and a fabricated team id returned {@code 200} and
 * stored both.
 *
 * <p><b>The HTTP half of that rule is unreachable since the write endpoints bind
 * {@code ProfileDTO}.</b> {@code specialtyCategoryId} and {@code teamIds} are not {@code ProfileDTO}
 * fields, so a {@code PUT} body naming them arrives with both ignored at the converter — the mapped
 * entity never introduces a reference, {@code OrganizationReferenceValidator} has nothing to
 * resolve, and (a {@code PUT} being a whole-document replace) the stored values are <b>cleared</b>
 * by any PUT, dangling or not. The PUT tests below assert that behaviour; the validator itself is
 * still exercised through {@code assignOrganization}, the one write path that can still name a
 * reference.
 *
 * <p>Every body carries {@code birthDate}, {@code sex}, {@code cardType} and {@code status} because
 * {@code ProfileMapper.toEntity} parses all four unconditionally.
 */
@AutoConfigureMockMvc
@IntegrationTest
@WithMockGatewayUser(authorities = { "ROLE_DOCTOR" })
class OrganizationReferenceIntegrityIT {

    private static final String ENTITY_API_URL_ID = "/api/profiles/{id}";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper om;

    @AfterEach
    void cleanup() {
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
        teamRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private Profile storedClinician() {
        return profileRepository.save(new Profile().accountId("item60-refs").firstName("Ama").lastName("Boateng"));
    }

    /**
     * A DTO-shaped body for {@code profile}, with {@code extraJsonFields} appended verbatim —
     * used to put {@code specialtyCategoryId}/{@code teamIds} on the wire, where the converter
     * ignores them as unknown keys.
     */
    private String dtoBody(Profile profile, String extraJsonFields) {
        return (
            "{\"id\":\"" +
            profile.getId() +
            "\",\"firstName\":\"" +
            profile.getFirstName() +
            "\",\"lastName\":\"" +
            profile.getLastName() +
            "\",\"birthDate\":\"1990-01-01\",\"sex\":\"FEMALE\",\"cardType\":\"GHANACARD\",\"status\":\"ACTIVE\"" +
            (extraJsonFields.isEmpty() ? "" : "," + extraJsonFields) +
            "}"
        );
    }

    // ---------------------------------------------------------------------------------------------
    // PUT /api/profiles/{id}
    // ---------------------------------------------------------------------------------------------

    /**
     * A category id on the wire — resolving or not — is an unknown key the converter ignores, so
     * the write succeeds and stores nothing in the field.
     */
    @Test
    void aCategoryOnTheWireIsIgnoredWhetherItResolvesOrNot() throws Exception {
        Category specialty = categoryRepository.save(new Category().name("Geriatric nursing"));
        Profile clinician = storedClinician();

        restMockMvc
            .perform(
                put(ENTITY_API_URL_ID, clinician.getId())
                    .contentType("application/json")
                    .content(dtoBody(clinician, "\"specialtyCategoryId\":\"" + specialty.getId() + "\""))
            )
            .andExpect(status().isOk());
        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getSpecialtyCategoryId()).isNull();

        restMockMvc
            .perform(
                put(ENTITY_API_URL_ID, clinician.getId())
                    .contentType("application/json")
                    .content(dtoBody(clinician, "\"specialtyCategoryId\":\"no-such-category\""))
            )
            .andExpect(status().isOk());
        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getSpecialtyCategoryId()).isNull();
    }

    /** The same for team ids, resolving and fabricated alike in one list. */
    @Test
    void teamIdsOnTheWireAreIgnoredWhetherTheyResolveOrNot() throws Exception {
        Team real = teamRepository.save(new Team().name("Home visits · North"));
        Profile clinician = storedClinician();

        restMockMvc
            .perform(
                put(ENTITY_API_URL_ID, clinician.getId())
                    .contentType("application/json")
                    .content(dtoBody(clinician, "\"teamIds\":[\"" + real.getId() + "\",\"no-such-team\"]"))
            )
            .andExpect(status().isOk());

        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getTeamIds()).isEmpty();
    }

    /** Naming neither still writes what the DTO does carry. */
    @Test
    void aWriteThatNamesNeitherIsUntouched() throws Exception {
        Profile clinician = storedClinician();
        clinician.setFirstName("Adwoa");

        restMockMvc
            .perform(put(ENTITY_API_URL_ID, clinician.getId()).contentType("application/json").content(dtoBody(clinician, "")))
            .andExpect(status().isOk());

        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getFirstName()).isEqualTo("Adwoa");
    }

    /**
     * A stored organisation pointer — dangling or sound — does not survive a {@code PUT}: the
     * endpoint replaces the whole document with what the DTO carries, and the DTO cannot carry
     * either field. The caller cannot prevent this by echoing the values; they are ignored on the
     * way in.
     */
    @Test
    void aPutClearsStoredOrganisationPointersTheDtoCannotCarry() throws Exception {
        Profile clinician = storedClinician();
        clinician.setSpecialtyCategoryId("dangling-from-before");
        clinician.setTeamIds(List.of("dangling-team"));
        profileRepository.save(clinician);

        clinician.setFirstName("Adwoa");
        restMockMvc
            .perform(
                put(ENTITY_API_URL_ID, clinician.getId())
                    .contentType("application/json")
                    .content(dtoBody(clinician, "\"specialtyCategoryId\":\"dangling-from-before\",\"teamIds\":[\"dangling-team\"]"))
            )
            .andExpect(status().isOk());

        Profile after = profileRepository.findById(clinician.getId()).orElseThrow();
        assertThat(after.getFirstName()).isEqualTo("Adwoa");
        assertThat(after.getSpecialtyCategoryId()).isNull();
        assertThat(after.getTeamIds()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // OnboardingService.assignOrganization
    // ---------------------------------------------------------------------------------------------

    private ProfessionalApplication approvedApplicationFor(Profile profile) {
        return applicationRepository.save(
            new ProfessionalApplication().accountId(profile.getAccountId()).profileId(profile.getId()).status(ProfileStatus.APPROVED)
        );
    }

    @Test
    void assignOrganizationAcceptsIdsThatResolve() {
        Profile clinician = storedClinician();
        Category specialty = categoryRepository.save(new Category().name("Palliative care"));
        Team team = teamRepository.save(new Team().name("Rapid response"));
        ProfessionalApplication application = approvedApplicationFor(clinician);

        onboardingService.assignOrganization(application.getId(), specialty.getId(), List.of(team.getId()), null, "admin");

        Profile after = profileRepository.findById(clinician.getId()).orElseThrow();
        assertThat(after.getSpecialtyCategoryId()).isEqualTo(specialty.getId());
        assertThat(after.getTeamIds()).containsExactly(team.getId());
    }

    @Test
    void assignOrganizationRefusesACategoryThatResolvesToNothing() {
        Profile clinician = storedClinician();
        ProfessionalApplication application = approvedApplicationFor(clinician);

        assertThatThrownBy(() -> onboardingService.assignOrganization(application.getId(), "no-such-category", null, null, "admin"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("no-such-category")
            .hasMessageContaining("400");

        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getSpecialtyCategoryId())
            .as("a refused assignment writes nothing")
            .isNull();
        assertThat(applicationRepository.findById(application.getId()).orElseThrow().getStatus())
            .as("and does not advance the application either")
            .isEqualTo(ProfileStatus.APPROVED);
    }

    @Test
    void assignOrganizationRefusesATeamThatResolvesToNothing() {
        Profile clinician = storedClinician();
        ProfessionalApplication application = approvedApplicationFor(clinician);

        assertThatThrownBy(() -> onboardingService.assignOrganization(application.getId(), null, List.of("no-such-team"), null, "admin"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("no-such-team");

        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getTeamIds()).isEmpty();
    }

    /**
     * A null specialty is not a pointer to nothing, it is the absence of one — and it is the ordinary
     * case rather than a curiosity: the quality stack holds zero categories (backlog.md item 61), so
     * every professional seeded there is assigned with {@code specialtyCategoryId} null.
     */
    @Test
    void assignOrganizationStillAcceptsNoSpecialtyAndNoTeams() {
        Profile clinician = storedClinician();
        ProfessionalApplication application = approvedApplicationFor(clinician);

        onboardingService.assignOrganization(application.getId(), null, null, null, "admin");

        assertThat(applicationRepository.findById(application.getId()).orElseThrow().getStatus()).isEqualTo(
            ProfileStatus.ORGANIZATION_ASSIGNED
        );
    }
}
