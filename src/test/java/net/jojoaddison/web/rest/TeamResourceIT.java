package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.ProfileTestSamples;
import net.jojoaddison.domain.Team;
import net.jojoaddison.domain.TeamTestSamples;
import net.jojoaddison.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link TeamResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class TeamResourceIT {

    private static final String DEFAULT_NAME = "AAAAAAAAAA";
    private static final String UPDATED_NAME = "BBBBBBBBBB";

    private static final String DEFAULT_DESCRIPTION = "AAAAAAAAAA";
    private static final String UPDATED_DESCRIPTION = "BBBBBBBBBB";

    private static final Set<Profile> DEFAULT_MEMBERS = TeamTestSamples.getTeamSample1().getMembers();
    private static final Set<Profile> UPDATED_MEMBERS = TeamTestSamples.getTeamSample2().getMembers();

    private static final Profile DEFAULT_SUPERVISOR = ProfileTestSamples.getProfileRandomSampleGenerator();
    private static final Profile UPDATED_SUPERVISOR = ProfileTestSamples.getProfileRandomSampleGenerator();

    private static final Profile DEFAULT_MANAGER = ProfileTestSamples.getProfileRandomSampleGenerator();
    private static final Profile UPDATED_MANAGER = ProfileTestSamples.getProfileRandomSampleGenerator();

    private static final String ENTITY_API_URL = "/api/teams";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";
    private static final String ENTITY_SEARCH_API_URL = "/api/teams/_search";

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private MockMvc restTeamMockMvc;

    private Team team;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Team createEntity() {
        Team team = new Team()
            .name(DEFAULT_NAME)
            .description(DEFAULT_DESCRIPTION)
            .members(DEFAULT_MEMBERS)
            .supervisor(DEFAULT_SUPERVISOR)
            .manager(DEFAULT_MANAGER);
        return team;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Team createUpdatedEntity() {
        Team team = new Team()
            .name(UPDATED_NAME)
            .description(UPDATED_DESCRIPTION)
            .members(UPDATED_MEMBERS)
            .supervisor(UPDATED_SUPERVISOR)
            .manager(UPDATED_MANAGER);
        return team;
    }

    @BeforeEach
    public void initTest() {
        teamRepository.deleteAll();
        team = createEntity();
    }

    @Test
    void createTeam() throws Exception {
        int databaseSizeBeforeCreate = teamRepository.findAll().size();
        // Create the Team
        restTeamMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(team)))
            .andExpect(status().isCreated());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeCreate + 1);
        Team testTeam = teamList.get(teamList.size() - 1);
        assertThat(testTeam.getName()).isEqualTo(DEFAULT_NAME);
        assertThat(testTeam.getDescription()).isEqualTo(DEFAULT_DESCRIPTION);
        assertThat(testTeam.getMembers()).isEqualTo(DEFAULT_MEMBERS);
        assertThat(testTeam.getSupervisor()).isEqualTo(DEFAULT_SUPERVISOR);
        assertThat(testTeam.getManager()).isEqualTo(DEFAULT_MANAGER);
    }

    @Test
    void createTeamWithExistingId() throws Exception {
        // Create the Team with an existing ID
        team.setId("existing_id");

        int databaseSizeBeforeCreate = teamRepository.findAll().size();

        // An entity with an existing ID cannot be created, so this API call must fail
        restTeamMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(team)))
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    void getAllTeams() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        // Get all the teamList
        restTeamMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(team.getId())))
            .andExpect(jsonPath("$.[*].name").value(hasItem(DEFAULT_NAME)))
            .andExpect(jsonPath("$.[*].description").value(hasItem(DEFAULT_DESCRIPTION)))
            .andExpect(jsonPath("$.[*].members").value(hasItem(DEFAULT_MEMBERS)))
            .andExpect(jsonPath("$.[*].supervisor").value(hasItem(DEFAULT_SUPERVISOR)))
            .andExpect(jsonPath("$.[*].manager").value(hasItem(DEFAULT_MANAGER)));
    }

    @Test
    void getTeam() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        // Get the team
        restTeamMockMvc
            .perform(get(ENTITY_API_URL_ID, team.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(team.getId()))
            .andExpect(jsonPath("$.name").value(DEFAULT_NAME))
            .andExpect(jsonPath("$.description").value(DEFAULT_DESCRIPTION))
            .andExpect(jsonPath("$.members").value(DEFAULT_MEMBERS))
            .andExpect(jsonPath("$.supervisor").value(DEFAULT_SUPERVISOR))
            .andExpect(jsonPath("$.manager").value(DEFAULT_MANAGER));
    }

    @Test
    void getNonExistingTeam() throws Exception {
        // Get the team
        restTeamMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingTeam() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        int databaseSizeBeforeUpdate = teamRepository.findAll().size();

        // Update the team
        Team updatedTeam = teamRepository.findById(team.getId()).orElseThrow();
        updatedTeam
            .name(UPDATED_NAME)
            .description(UPDATED_DESCRIPTION)
            .members(UPDATED_MEMBERS)
            .supervisor(UPDATED_SUPERVISOR)
            .manager(UPDATED_MANAGER);

        restTeamMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedTeam.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(updatedTeam))
            )
            .andExpect(status().isOk());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
        Team testTeam = teamList.get(teamList.size() - 1);
        assertThat(testTeam.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testTeam.getDescription()).isEqualTo(UPDATED_DESCRIPTION);
        assertThat(testTeam.getMembers()).isEqualTo(UPDATED_MEMBERS);
        assertThat(testTeam.getSupervisor()).isEqualTo(UPDATED_SUPERVISOR);
        assertThat(testTeam.getManager()).isEqualTo(UPDATED_MANAGER);
    }

    @Test
    void putNonExistingTeam() throws Exception {
        int databaseSizeBeforeUpdate = teamRepository.findAll().size();
        team.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(
                put(ENTITY_API_URL_ID, team.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(team))
            )
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchTeam() throws Exception {
        int databaseSizeBeforeUpdate = teamRepository.findAll().size();
        team.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(team))
            )
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamTeam() throws Exception {
        int databaseSizeBeforeUpdate = teamRepository.findAll().size();
        team.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(team)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateTeamWithPatch() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        int databaseSizeBeforeUpdate = teamRepository.findAll().size();

        // Update the team using partial update
        Team partialUpdatedTeam = new Team();
        partialUpdatedTeam.setId(team.getId());

        partialUpdatedTeam.name(UPDATED_NAME).supervisor(UPDATED_SUPERVISOR).manager(UPDATED_MANAGER);

        restTeamMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedTeam.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedTeam))
            )
            .andExpect(status().isOk());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
        Team testTeam = teamList.get(teamList.size() - 1);
        assertThat(testTeam.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testTeam.getDescription()).isEqualTo(DEFAULT_DESCRIPTION);
        assertThat(testTeam.getMembers()).isEqualTo(DEFAULT_MEMBERS);
        assertThat(testTeam.getSupervisor()).isEqualTo(UPDATED_SUPERVISOR);
        assertThat(testTeam.getManager()).isEqualTo(UPDATED_MANAGER);
    }

    @Test
    void fullUpdateTeamWithPatch() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        int databaseSizeBeforeUpdate = teamRepository.findAll().size();

        // Update the team using partial update
        Team partialUpdatedTeam = new Team();
        partialUpdatedTeam.setId(team.getId());

        partialUpdatedTeam
            .name(UPDATED_NAME)
            .description(UPDATED_DESCRIPTION)
            .members(UPDATED_MEMBERS)
            .supervisor(UPDATED_SUPERVISOR)
            .manager(UPDATED_MANAGER);

        restTeamMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedTeam.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedTeam))
            )
            .andExpect(status().isOk());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
        Team testTeam = teamList.get(teamList.size() - 1);
        assertThat(testTeam.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testTeam.getDescription()).isEqualTo(UPDATED_DESCRIPTION);
        assertThat(testTeam.getMembers()).isEqualTo(UPDATED_MEMBERS);
        assertThat(testTeam.getSupervisor()).isEqualTo(UPDATED_SUPERVISOR);
        assertThat(testTeam.getManager()).isEqualTo(UPDATED_MANAGER);
    }

    @Test
    void patchNonExistingTeam() throws Exception {
        int databaseSizeBeforeUpdate = teamRepository.findAll().size();
        team.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, team.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(team))
            )
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchTeam() throws Exception {
        int databaseSizeBeforeUpdate = teamRepository.findAll().size();
        team.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(team))
            )
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamTeam() throws Exception {
        int databaseSizeBeforeUpdate = teamRepository.findAll().size();
        team.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(TestUtil.convertObjectToJsonBytes(team)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Team in the database
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteTeam() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        int databaseSizeBeforeDelete = teamRepository.findAll().size();

        // Delete the team
        restTeamMockMvc
            .perform(delete(ENTITY_API_URL_ID, team.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        List<Team> teamList = teamRepository.findAll();
        assertThat(teamList).hasSize(databaseSizeBeforeDelete - 1);
    }

    @Test
    void searchTeam() throws Exception {
        // Initialize the database
        team = teamRepository.save(team);

        // Search the team
        restTeamMockMvc
            .perform(get(ENTITY_SEARCH_API_URL + "?query=id:" + team.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(team.getId())))
            .andExpect(jsonPath("$.[*].name").value(hasItem(DEFAULT_NAME)))
            .andExpect(jsonPath("$.[*].description").value(hasItem(DEFAULT_DESCRIPTION)))
            .andExpect(jsonPath("$.[*].members").value(hasItem(DEFAULT_MEMBERS)))
            .andExpect(jsonPath("$.[*].supervisor").value(hasItem(DEFAULT_SUPERVISOR)))
            .andExpect(jsonPath("$.[*].manager").value(hasItem(DEFAULT_MANAGER)));
    }
}
