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
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.HCPayOption;
import net.jojoaddison.repository.HCPayOptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link HCPayOptionResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class HCPayOptionResourceIT {

    private static final String DEFAULT_TYPE = "AAAAAAAAAA";
    private static final String UPDATED_TYPE = "BBBBBBBBBB";

    private static final String DEFAULT_USER_ID = "AAAAAAAAAA";
    private static final String UPDATED_USER_ID = "BBBBBBBBBB";

    private static final String DEFAULT_METADATA = "AAAAAAAAAA";
    private static final String UPDATED_METADATA = "BBBBBBBBBB";

    private static final String ENTITY_API_URL = "/api/hc-pay-options";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";
    private static final String ENTITY_SEARCH_API_URL = "/api/hc-pay-options/_search";

    @Autowired
    private HCPayOptionRepository hCPayOptionRepository;

    @Autowired
    private MockMvc restHCPayOptionMockMvc;

    private HCPayOption hCPayOption;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static HCPayOption createEntity() {
        HCPayOption hCPayOption = new HCPayOption().type(DEFAULT_TYPE).userID(DEFAULT_USER_ID).metadata(DEFAULT_METADATA);
        return hCPayOption;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static HCPayOption createUpdatedEntity() {
        HCPayOption hCPayOption = new HCPayOption().type(UPDATED_TYPE).userID(UPDATED_USER_ID).metadata(UPDATED_METADATA);
        return hCPayOption;
    }

    @BeforeEach
    public void initTest() {
        hCPayOptionRepository.deleteAll();
        hCPayOption = createEntity();
    }

    @Test
    void createHCPayOption() throws Exception {
        int databaseSizeBeforeCreate = hCPayOptionRepository.findAll().size();
        // Create the HCPayOption
        restHCPayOptionMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(hCPayOption)))
            .andExpect(status().isCreated());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeCreate + 1);
        HCPayOption testHCPayOption = hCPayOptionList.get(hCPayOptionList.size() - 1);
        assertThat(testHCPayOption.getType()).isEqualTo(DEFAULT_TYPE);
        assertThat(testHCPayOption.getUserID()).isEqualTo(DEFAULT_USER_ID);
        assertThat(testHCPayOption.getMetadata()).isEqualTo(DEFAULT_METADATA);
    }

    @Test
    void createHCPayOptionWithExistingId() throws Exception {
        // Create the HCPayOption with an existing ID
        hCPayOption.setId("existing_id");

        int databaseSizeBeforeCreate = hCPayOptionRepository.findAll().size();

        // An entity with an existing ID cannot be created, so this API call must fail
        restHCPayOptionMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(hCPayOption)))
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    void getAllHCPayOptions() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        // Get all the hCPayOptionList
        restHCPayOptionMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(hCPayOption.getId())))
            .andExpect(jsonPath("$.[*].type").value(hasItem(DEFAULT_TYPE)))
            .andExpect(jsonPath("$.[*].userID").value(hasItem(DEFAULT_USER_ID)))
            .andExpect(jsonPath("$.[*].metadata").value(hasItem(DEFAULT_METADATA)));
    }

    @Test
    void getHCPayOption() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        // Get the hCPayOption
        restHCPayOptionMockMvc
            .perform(get(ENTITY_API_URL_ID, hCPayOption.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(hCPayOption.getId()))
            .andExpect(jsonPath("$.type").value(DEFAULT_TYPE))
            .andExpect(jsonPath("$.userID").value(DEFAULT_USER_ID))
            .andExpect(jsonPath("$.metadata").value(DEFAULT_METADATA));
    }

    @Test
    void getNonExistingHCPayOption() throws Exception {
        // Get the hCPayOption
        restHCPayOptionMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingHCPayOption() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();

        // Update the hCPayOption
        HCPayOption updatedHCPayOption = hCPayOptionRepository.findById(hCPayOption.getId()).orElseThrow();
        updatedHCPayOption.type(UPDATED_TYPE).userID(UPDATED_USER_ID).metadata(UPDATED_METADATA);

        restHCPayOptionMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedHCPayOption.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(updatedHCPayOption))
            )
            .andExpect(status().isOk());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
        HCPayOption testHCPayOption = hCPayOptionList.get(hCPayOptionList.size() - 1);
        assertThat(testHCPayOption.getType()).isEqualTo(UPDATED_TYPE);
        assertThat(testHCPayOption.getUserID()).isEqualTo(UPDATED_USER_ID);
        assertThat(testHCPayOption.getMetadata()).isEqualTo(UPDATED_METADATA);
    }

    @Test
    void putNonExistingHCPayOption() throws Exception {
        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                put(ENTITY_API_URL_ID, hCPayOption.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(hCPayOption))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchHCPayOption() throws Exception {
        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(hCPayOption))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamHCPayOption() throws Exception {
        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(hCPayOption)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateHCPayOptionWithPatch() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();

        // Update the hCPayOption using partial update
        HCPayOption partialUpdatedHCPayOption = new HCPayOption();
        partialUpdatedHCPayOption.setId(hCPayOption.getId());

        partialUpdatedHCPayOption.type(UPDATED_TYPE).userID(UPDATED_USER_ID);

        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedHCPayOption.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedHCPayOption))
            )
            .andExpect(status().isOk());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
        HCPayOption testHCPayOption = hCPayOptionList.get(hCPayOptionList.size() - 1);
        assertThat(testHCPayOption.getType()).isEqualTo(UPDATED_TYPE);
        assertThat(testHCPayOption.getUserID()).isEqualTo(UPDATED_USER_ID);
        assertThat(testHCPayOption.getMetadata()).isEqualTo(DEFAULT_METADATA);
    }

    @Test
    void fullUpdateHCPayOptionWithPatch() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();

        // Update the hCPayOption using partial update
        HCPayOption partialUpdatedHCPayOption = new HCPayOption();
        partialUpdatedHCPayOption.setId(hCPayOption.getId());

        partialUpdatedHCPayOption.type(UPDATED_TYPE).userID(UPDATED_USER_ID).metadata(UPDATED_METADATA);

        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedHCPayOption.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedHCPayOption))
            )
            .andExpect(status().isOk());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
        HCPayOption testHCPayOption = hCPayOptionList.get(hCPayOptionList.size() - 1);
        assertThat(testHCPayOption.getType()).isEqualTo(UPDATED_TYPE);
        assertThat(testHCPayOption.getUserID()).isEqualTo(UPDATED_USER_ID);
        assertThat(testHCPayOption.getMetadata()).isEqualTo(UPDATED_METADATA);
    }

    @Test
    void patchNonExistingHCPayOption() throws Exception {
        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, hCPayOption.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(hCPayOption))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchHCPayOption() throws Exception {
        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(hCPayOption))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamHCPayOption() throws Exception {
        int databaseSizeBeforeUpdate = hCPayOptionRepository.findAll().size();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(TestUtil.convertObjectToJsonBytes(hCPayOption))
            )
            .andExpect(status().isMethodNotAllowed());

        // Validate the HCPayOption in the database
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteHCPayOption() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        int databaseSizeBeforeDelete = hCPayOptionRepository.findAll().size();

        // Delete the hCPayOption
        restHCPayOptionMockMvc
            .perform(delete(ENTITY_API_URL_ID, hCPayOption.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        List<HCPayOption> hCPayOptionList = hCPayOptionRepository.findAll();
        assertThat(hCPayOptionList).hasSize(databaseSizeBeforeDelete - 1);
    }

    @Test
    void searchHCPayOption() throws Exception {
        // Initialize the database
        hCPayOption = hCPayOptionRepository.save(hCPayOption);

        // Search the hCPayOption
        restHCPayOptionMockMvc
            .perform(get(ENTITY_SEARCH_API_URL + "?query=id:" + hCPayOption.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(hCPayOption.getId())))
            .andExpect(jsonPath("$.[*].type").value(hasItem(DEFAULT_TYPE)))
            .andExpect(jsonPath("$.[*].userID").value(hasItem(DEFAULT_USER_ID)))
            .andExpect(jsonPath("$.[*].metadata").value(hasItem(DEFAULT_METADATA)));
    }
}
