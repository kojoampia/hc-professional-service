package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Category;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Task;
import net.jojoaddison.domain.Team;
import net.jojoaddison.repository.CategoryRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import net.jojoaddison.repository.TeamRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Nothing a profile or a task points at can be deleted out from under it (backlog.md item 57).
 *
 * <p><b>The defect.</b> {@code Category} and {@code Team} were pointed at by id from three places —
 * {@code Profile.specialtyCategoryId}, {@code Profile.teamIds} and {@code Task.teamId} — and their
 * generated {@code DELETE} was a bare {@code deleteById} with no cascade and no announcement. So a
 * clinician's discipline or team assignment could be made to resolve to nothing by anyone holding
 * one of the six {@code CLINICAL_MUTATION} roles, silently, and nothing in this service or in
 * hc-admin would notice. Verified reachable on the quality stack before the fix: a
 * {@code ROLE_DOCTOR} token deleted a category with {@code 204} and left it {@code 404}.
 *
 * <p><b>Why this class exists beside the two absence tests in {@code CategoryResourceIT} and
 * {@code TeamResourceIT}.</b> Those assert that a path does not answer a verb, which is a fact about
 * a URL; a rename or a remount makes them pass while saying nothing. This one starts from the
 * pointer — it builds the orphan the defect produced, then asserts it cannot be produced — so it
 * states the property the removal exists for rather than the shape today's routing happens to have.
 * That is the same distinction {@code ProfileStatusOnEveryWriteIT} draws against the call-site table
 * it replaced (item 49).
 *
 * <p><b>The other half, and where it now lives.</b> This class says nothing about a pointer that never
 * resolved in the first place: neither {@code OnboardingService.assignOrganization} nor
 * {@code ProfileResource}'s whole-entity write checked that the ids they stored named existing rows,
 * so a typo produced by hand exactly the dangling pointer this class refuses to let a delete produce.
 * That gap was filed as backlog.md item 60 and closed on 2026-09-09 —
 * {@code OrganizationReferenceIntegrityIT} is its test. The two remain separate classes because they
 * state separate properties: this one is about what a delete may not take away, that one about what a
 * write may not introduce.
 *
 * <p>Run as {@code ROLE_DOCTOR}: one of the six the blanket {@code DELETE /api/**} rule admits, so
 * the refusals below are the mapping's absence and not the mutation matrix doing the work.
 */
@AutoConfigureMockMvc
@IntegrationTest
@WithMockGatewayUser(authorities = { "ROLE_DOCTOR" })
class ReferenceDataDeletionIT {

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private TaskRepository taskRepository;

    @AfterEach
    void cleanup() {
        taskRepository.deleteAll();
        profileRepository.deleteAll();
        teamRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    /**
     * The specialty a clinician is filed under cannot be removed while they are filed under it.
     *
     * <p>{@code specialtyCategoryId} is a lone id — no name beside it, no second field to fall back
     * on — so a deleted category leaves the profile naming a discipline that resolves to nothing, and
     * there is nothing in the profile from which to reconstruct what it was.
     */
    @Test
    void aCategoryAProfileIsFiledUnderCannotBeDeleted() throws Exception {
        Category specialty = categoryRepository.save(new Category().name("Geriatric nursing"));
        Profile clinician = profileRepository.save(
            new Profile().accountId("item57-clinician").firstName("Ama").lastName("Boateng").specialtyCategoryId(specialty.getId())
        );

        restMockMvc.perform(delete("/api/categories/{id}", specialty.getId())).andExpect(status().isMethodNotAllowed());

        String pointer = profileRepository.findById(clinician.getId()).orElseThrow().getSpecialtyCategoryId();
        assertThat(categoryRepository.findById(pointer)).as("the profile's specialty must still resolve").isPresent();
    }

    /**
     * A team a clinician is assigned to cannot be disbanded out from under the assignment.
     *
     * <p>{@code Profile.teamIds} is written on the ordinary admin path,
     * {@code OnboardingService.assignOrganization}, so this is not a hypothetical column.
     */
    @Test
    void aTeamAProfileIsAssignedToCannotBeDeleted() throws Exception {
        Team team = teamRepository.save(new Team().name("Home visits · North"));
        Profile clinician = profileRepository.save(
            new Profile().accountId("item57-assigned").firstName("Kwesi").lastName("Owusu").teamIds(List.of(team.getId()))
        );

        restMockMvc.perform(delete("/api/teams/{id}", team.getId())).andExpect(status().isMethodNotAllowed());

        List<String> pointers = profileRepository.findById(clinician.getId()).orElseThrow().getTeamIds();
        assertThat(pointers).containsExactly(team.getId());
        assertThat(teamRepository.findById(team.getId())).as("the profile's team must still resolve").isPresent();
    }

    /**
     * The second pointer at a team, and the one a sweep for "profile" would miss.
     *
     * <p>{@code Task.teamId} says which team owes the work. A deleted team leaves the task owed by
     * nobody, which is the same orphan one collection over — and it is why the answer here was
     * removing the endpoint rather than cascading into {@code Profile} alone.
     */
    @Test
    void aTeamATaskIsFiledAgainstCannotBeDeleted() throws Exception {
        Team team = teamRepository.save(new Team().name("Rapid response"));
        Task visit = taskRepository.save(new Task().name("Wound dressing").teamId(team.getId()));

        restMockMvc.perform(delete("/api/teams/{id}", team.getId())).andExpect(status().isMethodNotAllowed());

        String pointer = taskRepository.findById(visit.getId()).orElseThrow().getTeamId();
        assertThat(teamRepository.findById(pointer)).as("the task's team must still resolve").isPresent();
    }

    /**
     * The refusal is the missing mapping, not a missing row.
     *
     * <p>Asserted because 404 and 405 are easy to conflate and only one of them means what this
     * change did: the path pattern still serves GET, PUT and PATCH, so Spring rejects the method. A
     * regeneration that restored the mapping would answer 204 here, and a route that stopped
     * existing altogether would answer 404 — both are failures, and 405 is the only pass.
     */
    @Test
    void anIdThatNamesNothingIsStillRefusedByMethodRatherThanByRoute() throws Exception {
        restMockMvc.perform(delete("/api/categories/{id}", "no-such-category")).andExpect(status().isMethodNotAllowed());
        restMockMvc.perform(delete("/api/teams/{id}", "no-such-team")).andExpect(status().isMethodNotAllowed());
    }
}
