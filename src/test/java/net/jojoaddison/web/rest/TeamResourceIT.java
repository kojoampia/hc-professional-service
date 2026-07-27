package net.jojoaddison.web.rest;

import static net.jojoaddison.domain.TeamAsserts.*;
import static net.jojoaddison.web.rest.TestUtil.createUpdateProxyForBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Team;
import net.jojoaddison.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link TeamResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser(authorities = { "ROLE_DOCTOR" })
class TeamResourceIT {

    private static final String DEFAULT_NAME = "AAAAAAAAAA";
    private static final String UPDATED_NAME = "BBBBBBBBBB";

    private static final String DEFAULT_DESCRIPTION = "AAAAAAAAAA";
    private static final String UPDATED_DESCRIPTION = "BBBBBBBBBB";

    private static final java.util.List<String> DEFAULT_MEMBERS = java.util.List.of("AAAAAAAAAA");
    private static final java.util.List<String> UPDATED_MEMBERS = java.util.List.of("BBBBBBBBBB");

    private static final String DEFAULT_SUPERVISOR = "AAAAAAAAAA";
    private static final String UPDATED_SUPERVISOR = "BBBBBBBBBB";

    private static final String DEFAULT_MANAGER = "AAAAAAAAAA";
    private static final String UPDATED_MANAGER = "BBBBBBBBBB";

    private static final String ENTITY_API_URL = "/api/teams";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

    @Autowired
    private ObjectMapper om;

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
        long databaseSizeBeforeCreate = getRepositoryCount();
        // Create the Team
        var returnedTeam = om.readValue(
            restTeamMockMvc
                .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(team)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Team.class
        );

        // Validate the Team in the database
        assertIncrementedRepositoryCount(databaseSizeBeforeCreate);
        assertTeamUpdatableFieldsEquals(returnedTeam, getPersistedTeam(returnedTeam));
    }

    @Test
    void createTeamWithExistingId() throws Exception {
        // Create the Team with an existing ID
        team.setId("existing_id");

        long databaseSizeBeforeCreate = getRepositoryCount();

        // An entity with an existing ID cannot be created, so this API call must fail
        restTeamMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(team)))
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        assertSameRepositoryCount(databaseSizeBeforeCreate);
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
            .andExpect(jsonPath("$.members", org.hamcrest.Matchers.contains(DEFAULT_MEMBERS.toArray())))
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

        long databaseSizeBeforeUpdate = getRepositoryCount();

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
                    .content(om.writeValueAsBytes(updatedTeam))
            )
            .andExpect(status().isOk());

        // Validate the Team in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertPersistedTeamToMatchAllProperties(updatedTeam);
    }

    @Test
    void putNonExistingTeam() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        team.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(put(ENTITY_API_URL_ID, team.getId()).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(team)))
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchTeam() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        team.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(team))
            )
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamTeam() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        team.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(team)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Team in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateTeamWithPatch() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the team using partial update
        Team partialUpdatedTeam = new Team();
        partialUpdatedTeam.setId(team.getId());

        partialUpdatedTeam.name(UPDATED_NAME).supervisor(UPDATED_SUPERVISOR).manager(UPDATED_MANAGER);

        restTeamMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedTeam.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedTeam))
            )
            .andExpect(status().isOk());

        // Validate the Team in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertTeamUpdatableFieldsEquals(createUpdateProxyForBean(partialUpdatedTeam, team), getPersistedTeam(team));
    }

    @Test
    void fullUpdateTeamWithPatch() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        long databaseSizeBeforeUpdate = getRepositoryCount();

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
                    .content(om.writeValueAsBytes(partialUpdatedTeam))
            )
            .andExpect(status().isOk());

        // Validate the Team in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertTeamUpdatableFieldsEquals(partialUpdatedTeam, getPersistedTeam(partialUpdatedTeam));
    }

    @Test
    void patchNonExistingTeam() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        team.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(patch(ENTITY_API_URL_ID, team.getId()).contentType("application/merge-patch+json").content(om.writeValueAsBytes(team)))
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchTeam() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        team.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(team))
            )
            .andExpect(status().isBadRequest());

        // Validate the Team in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamTeam() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        team.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restTeamMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(om.writeValueAsBytes(team)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Team in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteTeam() throws Exception {
        // Initialize the database
        teamRepository.save(team);

        long databaseSizeBeforeDelete = getRepositoryCount();

        // Delete the team
        restTeamMockMvc
            .perform(delete(ENTITY_API_URL_ID, team.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        assertDecrementedRepositoryCount(databaseSizeBeforeDelete);
    }

    protected long getRepositoryCount() {
        return teamRepository.count();
    }

    protected void assertIncrementedRepositoryCount(long countBefore) {
        assertThat(countBefore + 1).isEqualTo(getRepositoryCount());
    }

    protected void assertDecrementedRepositoryCount(long countBefore) {
        assertThat(countBefore - 1).isEqualTo(getRepositoryCount());
    }

    protected void assertSameRepositoryCount(long countBefore) {
        assertThat(countBefore).isEqualTo(getRepositoryCount());
    }

    protected Team getPersistedTeam(Team team) {
        return teamRepository.findById(team.getId()).orElseThrow();
    }

    protected void assertPersistedTeamToMatchAllProperties(Team expectedTeam) {
        assertTeamAllPropertiesEquals(expectedTeam, getPersistedTeam(expectedTeam));
    }

    protected void assertPersistedTeamToMatchUpdatableProperties(Team expectedTeam) {
        assertTeamAllUpdatablePropertiesEquals(expectedTeam, getPersistedTeam(expectedTeam));
    }
}
