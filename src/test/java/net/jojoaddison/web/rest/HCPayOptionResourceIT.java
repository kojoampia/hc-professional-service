package net.jojoaddison.web.rest;

import static net.jojoaddison.domain.HCPayOptionAsserts.*;
import static net.jojoaddison.web.rest.TestUtil.createUpdateProxyForBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.HCPayOption;
import net.jojoaddison.repository.HCPayOptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

    @Autowired
    private ObjectMapper om;

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
        long databaseSizeBeforeCreate = getRepositoryCount();
        // Create the HCPayOption
        var returnedHCPayOption = om.readValue(
            restHCPayOptionMockMvc
                .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCPayOption)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            HCPayOption.class
        );

        // Validate the HCPayOption in the database
        assertIncrementedRepositoryCount(databaseSizeBeforeCreate);
        assertHCPayOptionUpdatableFieldsEquals(returnedHCPayOption, getPersistedHCPayOption(returnedHCPayOption));
    }

    @Test
    void createHCPayOptionWithExistingId() throws Exception {
        // Create the HCPayOption with an existing ID
        hCPayOption.setId("existing_id");

        long databaseSizeBeforeCreate = getRepositoryCount();

        // An entity with an existing ID cannot be created, so this API call must fail
        restHCPayOptionMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCPayOption)))
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        assertSameRepositoryCount(databaseSizeBeforeCreate);
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

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCPayOption
        HCPayOption updatedHCPayOption = hCPayOptionRepository.findById(hCPayOption.getId()).orElseThrow();
        updatedHCPayOption.type(UPDATED_TYPE).userID(UPDATED_USER_ID).metadata(UPDATED_METADATA);

        restHCPayOptionMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedHCPayOption.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(updatedHCPayOption))
            )
            .andExpect(status().isOk());

        // Validate the HCPayOption in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertPersistedHCPayOptionToMatchAllProperties(updatedHCPayOption);
    }

    @Test
    void putNonExistingHCPayOption() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                put(ENTITY_API_URL_ID, hCPayOption.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(hCPayOption))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchHCPayOption() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(hCPayOption))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamHCPayOption() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCPayOption)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the HCPayOption in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateHCPayOptionWithPatch() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCPayOption using partial update
        HCPayOption partialUpdatedHCPayOption = new HCPayOption();
        partialUpdatedHCPayOption.setId(hCPayOption.getId());

        partialUpdatedHCPayOption.type(UPDATED_TYPE).userID(UPDATED_USER_ID);

        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedHCPayOption.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedHCPayOption))
            )
            .andExpect(status().isOk());

        // Validate the HCPayOption in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertHCPayOptionUpdatableFieldsEquals(
            createUpdateProxyForBean(partialUpdatedHCPayOption, hCPayOption),
            getPersistedHCPayOption(hCPayOption)
        );
    }

    @Test
    void fullUpdateHCPayOptionWithPatch() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCPayOption using partial update
        HCPayOption partialUpdatedHCPayOption = new HCPayOption();
        partialUpdatedHCPayOption.setId(hCPayOption.getId());

        partialUpdatedHCPayOption.type(UPDATED_TYPE).userID(UPDATED_USER_ID).metadata(UPDATED_METADATA);

        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedHCPayOption.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedHCPayOption))
            )
            .andExpect(status().isOk());

        // Validate the HCPayOption in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertHCPayOptionUpdatableFieldsEquals(partialUpdatedHCPayOption, getPersistedHCPayOption(partialUpdatedHCPayOption));
    }

    @Test
    void patchNonExistingHCPayOption() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, hCPayOption.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(hCPayOption))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchHCPayOption() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(hCPayOption))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCPayOption in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamHCPayOption() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCPayOption.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCPayOptionMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(om.writeValueAsBytes(hCPayOption)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the HCPayOption in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteHCPayOption() throws Exception {
        // Initialize the database
        hCPayOptionRepository.save(hCPayOption);

        long databaseSizeBeforeDelete = getRepositoryCount();

        // Delete the hCPayOption
        restHCPayOptionMockMvc
            .perform(delete(ENTITY_API_URL_ID, hCPayOption.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        assertDecrementedRepositoryCount(databaseSizeBeforeDelete);
    }

    protected long getRepositoryCount() {
        return hCPayOptionRepository.count();
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

    protected HCPayOption getPersistedHCPayOption(HCPayOption hCPayOption) {
        return hCPayOptionRepository.findById(hCPayOption.getId()).orElseThrow();
    }

    protected void assertPersistedHCPayOptionToMatchAllProperties(HCPayOption expectedHCPayOption) {
        assertHCPayOptionAllPropertiesEquals(expectedHCPayOption, getPersistedHCPayOption(expectedHCPayOption));
    }

    protected void assertPersistedHCPayOptionToMatchUpdatableProperties(HCPayOption expectedHCPayOption) {
        assertHCPayOptionAllUpdatablePropertiesEquals(expectedHCPayOption, getPersistedHCPayOption(expectedHCPayOption));
    }
}
