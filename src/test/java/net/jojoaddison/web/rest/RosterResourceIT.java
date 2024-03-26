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

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Roster;
import net.jojoaddison.repository.RosterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link RosterResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class RosterResourceIT {

    private static final String DEFAULT_NAME = "AAAAAAAAAA";
    private static final String UPDATED_NAME = "BBBBBBBBBB";

    private static final String DEFAULT_DESCRIPTION = "AAAAAAAAAA";
    private static final String UPDATED_DESCRIPTION = "BBBBBBBBBB";

    private static final String DEFAULT_PROFESSIONAL_ID = "AAAAAAAAAA";
    private static final String UPDATED_PROFESSIONAL_ID = "BBBBBBBBBB";

    private static final LocalDate DEFAULT_SCHEDULE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_SCHEDULE = LocalDate.now(ZoneId.systemDefault());

    private static final Duration DEFAULT_DURATION = Duration.ofHours(6);
    private static final Duration UPDATED_DURATION = Duration.ofHours(12);

    private static final String DEFAULT_TASKS = "AAAAAAAAAA";
    private static final String UPDATED_TASKS = "BBBBBBBBBB";

    private static final String DEFAULT_CREATED_DATE = "AAAAAAAAAA";
    private static final String UPDATED_CREATED_DATE = "BBBBBBBBBB";

    private static final LocalDate DEFAULT_MODIFIED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_MODIFIED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final String DEFAULT_CREATED_BY = "AAAAAAAAAA";
    private static final String UPDATED_CREATED_BY = "BBBBBBBBBB";

    private static final String DEFAULT_MODIFIED_BY = "AAAAAAAAAA";
    private static final String UPDATED_MODIFIED_BY = "BBBBBBBBBB";

    private static final String ENTITY_API_URL = "/api/rosters";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

    @Autowired
    private RosterRepository rosterRepository;

    @Autowired
    private MockMvc restRosterMockMvc;

    private Roster roster;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Roster createEntity() {
        Roster roster = new Roster()
            .name(DEFAULT_NAME)
            .description(DEFAULT_DESCRIPTION)
            .professionalId(DEFAULT_PROFESSIONAL_ID)
            .schedule(DEFAULT_SCHEDULE)
            .duration(DEFAULT_DURATION)
            .tasks(DEFAULT_TASKS)
            .createdDate(DEFAULT_CREATED_DATE)
            .modifiedDate(DEFAULT_MODIFIED_DATE)
            .createdBy(DEFAULT_CREATED_BY)
            .modifiedBy(DEFAULT_MODIFIED_BY);
        return roster;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Roster createUpdatedEntity() {
        Roster roster = new Roster()
            .name(UPDATED_NAME)
            .description(UPDATED_DESCRIPTION)
            .professionalId(UPDATED_PROFESSIONAL_ID)
            .schedule(UPDATED_SCHEDULE)
            .duration(UPDATED_DURATION)
            .tasks(UPDATED_TASKS)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);
        return roster;
    }

    @BeforeEach
    public void initTest() {
        rosterRepository.deleteAll();
        roster = createEntity();
    }

    @Test
    void createRoster() throws Exception {
        int databaseSizeBeforeCreate = rosterRepository.findAll().size();
        // Create the Roster
        restRosterMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(roster)))
            .andExpect(status().isCreated());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeCreate + 1);
        Roster testRoster = rosterList.get(rosterList.size() - 1);
        assertThat(testRoster.getName()).isEqualTo(DEFAULT_NAME);
        assertThat(testRoster.getDescription()).isEqualTo(DEFAULT_DESCRIPTION);
        assertThat(testRoster.getProfessionalId()).isEqualTo(DEFAULT_PROFESSIONAL_ID);
        assertThat(testRoster.getSchedule()).isEqualTo(DEFAULT_SCHEDULE);
        assertThat(testRoster.getDuration()).isEqualTo(DEFAULT_DURATION);
        assertThat(testRoster.getTasks()).isEqualTo(DEFAULT_TASKS);
        assertThat(testRoster.getCreatedDate()).isEqualTo(DEFAULT_CREATED_DATE);
        assertThat(testRoster.getModifiedDate()).isEqualTo(DEFAULT_MODIFIED_DATE);
        assertThat(testRoster.getCreatedBy()).isEqualTo(DEFAULT_CREATED_BY);
        assertThat(testRoster.getModifiedBy()).isEqualTo(DEFAULT_MODIFIED_BY);
    }

    @Test
    void createRosterWithExistingId() throws Exception {
        // Create the Roster with an existing ID
        roster.setId("existing_id");

        int databaseSizeBeforeCreate = rosterRepository.findAll().size();

        // An entity with an existing ID cannot be created, so this API call must fail
        restRosterMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(roster)))
            .andExpect(status().isBadRequest());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    void getAllRosters() throws Exception {
        // Initialize the database
        rosterRepository.save(roster);

        // Get all the rosterList
        restRosterMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(roster.getId())))
            .andExpect(jsonPath("$.[*].name").value(hasItem(DEFAULT_NAME)))
            .andExpect(jsonPath("$.[*].description").value(hasItem(DEFAULT_DESCRIPTION)))
            .andExpect(jsonPath("$.[*].professionalId").value(hasItem(DEFAULT_PROFESSIONAL_ID)))
            .andExpect(jsonPath("$.[*].schedule").value(hasItem(DEFAULT_SCHEDULE.toString())))
            .andExpect(jsonPath("$.[*].duration").value(hasItem(DEFAULT_DURATION.toString())))
            .andExpect(jsonPath("$.[*].tasks").value(hasItem(DEFAULT_TASKS)))
            .andExpect(jsonPath("$.[*].createdDate").value(hasItem(DEFAULT_CREATED_DATE)))
            .andExpect(jsonPath("$.[*].modifiedDate").value(hasItem(DEFAULT_MODIFIED_DATE.toString())))
            .andExpect(jsonPath("$.[*].createdBy").value(hasItem(DEFAULT_CREATED_BY)))
            .andExpect(jsonPath("$.[*].modifiedBy").value(hasItem(DEFAULT_MODIFIED_BY)));
    }

    @Test
    void getRoster() throws Exception {
        // Initialize the database
        rosterRepository.save(roster);

        // Get the roster
        restRosterMockMvc
            .perform(get(ENTITY_API_URL_ID, roster.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(roster.getId()))
            .andExpect(jsonPath("$.name").value(DEFAULT_NAME))
            .andExpect(jsonPath("$.description").value(DEFAULT_DESCRIPTION))
            .andExpect(jsonPath("$.professionalId").value(DEFAULT_PROFESSIONAL_ID))
            .andExpect(jsonPath("$.schedule").value(DEFAULT_SCHEDULE.toString()))
            .andExpect(jsonPath("$.duration").value(DEFAULT_DURATION.toString()))
            .andExpect(jsonPath("$.tasks").value(DEFAULT_TASKS))
            .andExpect(jsonPath("$.createdDate").value(DEFAULT_CREATED_DATE))
            .andExpect(jsonPath("$.modifiedDate").value(DEFAULT_MODIFIED_DATE.toString()))
            .andExpect(jsonPath("$.createdBy").value(DEFAULT_CREATED_BY))
            .andExpect(jsonPath("$.modifiedBy").value(DEFAULT_MODIFIED_BY));
    }

    @Test
    void getNonExistingRoster() throws Exception {
        // Get the roster
        restRosterMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingRoster() throws Exception {
        // Initialize the database
        rosterRepository.save(roster);

        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();

        // Update the roster
        Roster updatedRoster = rosterRepository.findById(roster.getId()).orElseThrow();
        updatedRoster
            .name(UPDATED_NAME)
            .description(UPDATED_DESCRIPTION)
            .professionalId(UPDATED_PROFESSIONAL_ID)
            .schedule(UPDATED_SCHEDULE)
            .duration(UPDATED_DURATION)
            .tasks(UPDATED_TASKS)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);

        restRosterMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedRoster.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(updatedRoster))
            )
            .andExpect(status().isOk());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
        Roster testRoster = rosterList.get(rosterList.size() - 1);
        assertThat(testRoster.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testRoster.getDescription()).isEqualTo(UPDATED_DESCRIPTION);
        assertThat(testRoster.getProfessionalId()).isEqualTo(UPDATED_PROFESSIONAL_ID);
        assertThat(testRoster.getSchedule()).isEqualTo(UPDATED_SCHEDULE);
        assertThat(testRoster.getDuration()).isEqualTo(UPDATED_DURATION);
        assertThat(testRoster.getTasks()).isEqualTo(UPDATED_TASKS);
        assertThat(testRoster.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testRoster.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testRoster.getCreatedBy()).isEqualTo(UPDATED_CREATED_BY);
        assertThat(testRoster.getModifiedBy()).isEqualTo(UPDATED_MODIFIED_BY);
    }

    @Test
    void putNonExistingRoster() throws Exception {
        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();
        roster.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restRosterMockMvc
            .perform(
                put(ENTITY_API_URL_ID, roster.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(roster))
            )
            .andExpect(status().isBadRequest());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchRoster() throws Exception {
        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();
        roster.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restRosterMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(roster))
            )
            .andExpect(status().isBadRequest());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamRoster() throws Exception {
        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();
        roster.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restRosterMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(roster)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateRosterWithPatch() throws Exception {
        // Initialize the database
        rosterRepository.save(roster);

        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();

        // Update the roster using partial update
        Roster partialUpdatedRoster = new Roster();
        partialUpdatedRoster.setId(roster.getId());

        partialUpdatedRoster
            .professionalId(UPDATED_PROFESSIONAL_ID)
            .tasks(UPDATED_TASKS)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY);

        restRosterMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedRoster.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedRoster))
            )
            .andExpect(status().isOk());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
        Roster testRoster = rosterList.get(rosterList.size() - 1);
        assertThat(testRoster.getName()).isEqualTo(DEFAULT_NAME);
        assertThat(testRoster.getDescription()).isEqualTo(DEFAULT_DESCRIPTION);
        assertThat(testRoster.getProfessionalId()).isEqualTo(UPDATED_PROFESSIONAL_ID);
        assertThat(testRoster.getSchedule()).isEqualTo(DEFAULT_SCHEDULE);
        assertThat(testRoster.getDuration()).isEqualTo(DEFAULT_DURATION);
        assertThat(testRoster.getTasks()).isEqualTo(UPDATED_TASKS);
        assertThat(testRoster.getCreatedDate()).isEqualTo(DEFAULT_CREATED_DATE);
        assertThat(testRoster.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testRoster.getCreatedBy()).isEqualTo(UPDATED_CREATED_BY);
        assertThat(testRoster.getModifiedBy()).isEqualTo(DEFAULT_MODIFIED_BY);
    }

    @Test
    void fullUpdateRosterWithPatch() throws Exception {
        // Initialize the database
        rosterRepository.save(roster);

        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();

        // Update the roster using partial update
        Roster partialUpdatedRoster = new Roster();
        partialUpdatedRoster.setId(roster.getId());

        partialUpdatedRoster
            .name(UPDATED_NAME)
            .description(UPDATED_DESCRIPTION)
            .professionalId(UPDATED_PROFESSIONAL_ID)
            .schedule(UPDATED_SCHEDULE)
            .duration(UPDATED_DURATION)
            .tasks(UPDATED_TASKS)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);

        restRosterMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedRoster.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedRoster))
            )
            .andExpect(status().isOk());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
        Roster testRoster = rosterList.get(rosterList.size() - 1);
        assertThat(testRoster.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testRoster.getDescription()).isEqualTo(UPDATED_DESCRIPTION);
        assertThat(testRoster.getProfessionalId()).isEqualTo(UPDATED_PROFESSIONAL_ID);
        assertThat(testRoster.getSchedule()).isEqualTo(UPDATED_SCHEDULE);
        assertThat(testRoster.getDuration()).isEqualTo(UPDATED_DURATION);
        assertThat(testRoster.getTasks()).isEqualTo(UPDATED_TASKS);
        assertThat(testRoster.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testRoster.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testRoster.getCreatedBy()).isEqualTo(UPDATED_CREATED_BY);
        assertThat(testRoster.getModifiedBy()).isEqualTo(UPDATED_MODIFIED_BY);
    }

    @Test
    void patchNonExistingRoster() throws Exception {
        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();
        roster.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restRosterMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, roster.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(roster))
            )
            .andExpect(status().isBadRequest());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchRoster() throws Exception {
        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();
        roster.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restRosterMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(roster))
            )
            .andExpect(status().isBadRequest());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamRoster() throws Exception {
        int databaseSizeBeforeUpdate = rosterRepository.findAll().size();
        roster.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restRosterMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(TestUtil.convertObjectToJsonBytes(roster)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Roster in the database
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteRoster() throws Exception {
        // Initialize the database
        rosterRepository.save(roster);

        int databaseSizeBeforeDelete = rosterRepository.findAll().size();

        // Delete the roster
        restRosterMockMvc
            .perform(delete(ENTITY_API_URL_ID, roster.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        List<Roster> rosterList = rosterRepository.findAll();
        assertThat(rosterList).hasSize(databaseSizeBeforeDelete - 1);
    }
}
