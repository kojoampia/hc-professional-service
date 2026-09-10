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
import net.jojoaddison.domain.enumeration.OnboardingStatus;
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
 * <p><b>Two write paths, and they are checked differently on purpose.</b>
 * {@code assignOrganization} is <em>the</em> assignment operation, so it validates whatever it is
 * given, every time — an admin naming a category that does not exist has made a mistake worth being
 * told about even if the profile already held that value. {@code PUT /api/profiles/&#123;id&#125;} is
 * a generic whole-document replace, so it validates only what the write <em>introduces</em>: a
 * document already holding a pointer that resolves to nothing must stay editable, because repairing
 * pointers that were already dangling before this change is explicitly not item 60 and a caller who
 * did not create the problem should not be the one blocked by it.
 *
 * <p><b>{@code PATCH} appears nowhere here</b>, and that is not an omission: it refuses both fields
 * outright — see {@code ProfilePatchFieldCoverageIT} — so there is no write of theirs left to
 * validate.
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

    private String whole(Profile profile) throws Exception {
        return om.writeValueAsString(profile);
    }

    // ---------------------------------------------------------------------------------------------
    // PUT /api/profiles/{id}
    // ---------------------------------------------------------------------------------------------

    /** The control: an id that resolves is stored, so the refusals below are about resolution. */
    @Test
    void aCategoryThatResolvesIsAccepted() throws Exception {
        Category specialty = categoryRepository.save(new Category().name("Geriatric nursing"));
        Profile clinician = storedClinician();
        clinician.setSpecialtyCategoryId(specialty.getId());

        restMockMvc
            .perform(put(ENTITY_API_URL_ID, clinician.getId()).contentType("application/json").content(whole(clinician)))
            .andExpect(status().isOk());

        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getSpecialtyCategoryId()).isEqualTo(specialty.getId());
    }

    @Test
    void aCategoryThatResolvesToNothingIsRefused() throws Exception {
        Profile clinician = storedClinician();
        clinician.setSpecialtyCategoryId("no-such-category");

        String body = restMockMvc
            .perform(put(ENTITY_API_URL_ID, clinician.getId()).contentType("application/json").content(whole(clinician)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body).as("the refusal must name what did not resolve").contains("no-such-category");
        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getSpecialtyCategoryId()).isNull();
    }

    /**
     * One bad id in a list of otherwise good ones is still a bad write. Asserted with a resolving
     * team beside it so that the refusal cannot be an artefact of the list being non-empty.
     */
    @Test
    void aTeamThatResolvesToNothingIsRefusedEvenBesideOneThatDoes() throws Exception {
        Team real = teamRepository.save(new Team().name("Home visits · North"));
        Profile clinician = storedClinician();
        clinician.setTeamIds(List.of(real.getId(), "no-such-team"));

        String body = restMockMvc
            .perform(put(ENTITY_API_URL_ID, clinician.getId()).contentType("application/json").content(whole(clinician)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body).contains("no-such-team");
        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getTeamIds()).isEmpty();
    }

    /** Naming neither is not naming something that does not resolve. */
    @Test
    void aWriteThatNamesNeitherIsUntouched() throws Exception {
        Profile clinician = storedClinician();
        clinician.setFirstName("Adwoa");

        restMockMvc
            .perform(put(ENTITY_API_URL_ID, clinician.getId()).contentType("application/json").content(whole(clinician)))
            .andExpect(status().isOk());

        assertThat(profileRepository.findById(clinician.getId()).orElseThrow().getFirstName()).isEqualTo("Adwoa");
    }

    /**
     * A profile that already holds a pointer to nothing stays editable.
     *
     * <p>Repairing pointers that were dangling before this change is out of item 60's scope, and a
     * rule that validated the resulting <em>state</em> rather than the change would quietly make
     * those profiles unwritable through {@code PUT} — which is a whole-document replace, so a caller
     * cannot avoid carrying the field forward.
     */
    @Test
    void anAlreadyDanglingPointerCarriedForwardUnchangedIsNotRefused() throws Exception {
        Profile clinician = storedClinician();
        clinician.setSpecialtyCategoryId("dangling-from-before");
        clinician.setTeamIds(List.of("dangling-team"));
        profileRepository.save(clinician);

        clinician.setFirstName("Adwoa");
        restMockMvc
            .perform(put(ENTITY_API_URL_ID, clinician.getId()).contentType("application/json").content(whole(clinician)))
            .andExpect(status().isOk());

        Profile after = profileRepository.findById(clinician.getId()).orElseThrow();
        assertThat(after.getFirstName()).isEqualTo("Adwoa");
        assertThat(after.getSpecialtyCategoryId()).isEqualTo("dangling-from-before");
    }

    // ---------------------------------------------------------------------------------------------
    // OnboardingService.assignOrganization
    // ---------------------------------------------------------------------------------------------

    private ProfessionalApplication approvedApplicationFor(Profile profile) {
        return applicationRepository.save(
            new ProfessionalApplication().accountId(profile.getAccountId()).profileId(profile.getId()).status(OnboardingStatus.APPROVED)
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
            .isEqualTo(OnboardingStatus.APPROVED);
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
            OnboardingStatus.ORGANIZATION_ASSIGNED
        );
    }
}
