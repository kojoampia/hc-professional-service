package net.jojoaddison.web.rest;

import static net.jojoaddison.domain.MetadataAsserts.*;
import static net.jojoaddison.web.rest.TestUtil.createUpdateProxyForBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Metadata;
import net.jojoaddison.repository.MetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link MetadataResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class MetadataResourceIT {

    private static final String DEFAULT_CREATED_BY = "AAAAAAAAAA";
    private static final String UPDATED_CREATED_BY = "BBBBBBBBBB";

    private static final String DEFAULT_MODIFIED_BY = "AAAAAAAAAA";
    private static final String UPDATED_MODIFIED_BY = "BBBBBBBBBB";

    private static final LocalDate DEFAULT_CREATED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_CREATED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final LocalDate DEFAULT_MODIFIED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_MODIFIED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final String DEFAULT_DATA = "AAAAAAAAAA";
    private static final String UPDATED_DATA = "BBBBBBBBBB";

    private static final String ENTITY_API_URL = "/api/metadata";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private MetadataRepository metadataRepository;

    @Autowired
    private MockMvc restMetadataMockMvc;

    private Metadata metadata;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Metadata createEntity() {
        Metadata metadata = new Metadata()
            .createdBy(DEFAULT_CREATED_BY)
            .modifiedBy(DEFAULT_MODIFIED_BY)
            .createdDate(DEFAULT_CREATED_DATE)
            .modifiedDate(DEFAULT_MODIFIED_DATE)
            .data(DEFAULT_DATA);
        return metadata;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Metadata createUpdatedEntity() {
        Metadata metadata = new Metadata()
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .data(UPDATED_DATA);
        return metadata;
    }

    @BeforeEach
    public void initTest() {
        metadataRepository.deleteAll();
        metadata = createEntity();
    }

    @Test
    void createMetadata() throws Exception {
        long databaseSizeBeforeCreate = getRepositoryCount();
        // Create the Metadata
        var returnedMetadata = om.readValue(
            restMetadataMockMvc
                .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(metadata)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Metadata.class
        );

        // Validate the Metadata in the database
        assertIncrementedRepositoryCount(databaseSizeBeforeCreate);
        assertMetadataUpdatableFieldsEquals(returnedMetadata, getPersistedMetadata(returnedMetadata));
    }

    @Test
    void createMetadataWithExistingId() throws Exception {
        // Create the Metadata with an existing ID
        metadata.setId("existing_id");

        long databaseSizeBeforeCreate = getRepositoryCount();

        // An entity with an existing ID cannot be created, so this API call must fail
        restMetadataMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(metadata)))
            .andExpect(status().isBadRequest());

        // Validate the Metadata in the database
        assertSameRepositoryCount(databaseSizeBeforeCreate);
    }

    @Test
    void getAllMetadata() throws Exception {
        // Initialize the database
        metadataRepository.save(metadata);

        // Get all the metadataList
        restMetadataMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(metadata.getId())))
            .andExpect(jsonPath("$.[*].createdBy").value(hasItem(DEFAULT_CREATED_BY)))
            .andExpect(jsonPath("$.[*].modifiedBy").value(hasItem(DEFAULT_MODIFIED_BY)))
            .andExpect(jsonPath("$.[*].createdDate").value(hasItem(DEFAULT_CREATED_DATE.toString())))
            .andExpect(jsonPath("$.[*].modifiedDate").value(hasItem(DEFAULT_MODIFIED_DATE.toString())))
            .andExpect(jsonPath("$.[*].data").value(hasItem(DEFAULT_DATA)));
    }

    @Test
    void getMetadata() throws Exception {
        // Initialize the database
        metadataRepository.save(metadata);

        // Get the metadata
        restMetadataMockMvc
            .perform(get(ENTITY_API_URL_ID, metadata.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(metadata.getId()))
            .andExpect(jsonPath("$.createdBy").value(DEFAULT_CREATED_BY))
            .andExpect(jsonPath("$.modifiedBy").value(DEFAULT_MODIFIED_BY))
            .andExpect(jsonPath("$.createdDate").value(DEFAULT_CREATED_DATE.toString()))
            .andExpect(jsonPath("$.modifiedDate").value(DEFAULT_MODIFIED_DATE.toString()))
            .andExpect(jsonPath("$.data").value(DEFAULT_DATA));
    }

    @Test
    void getNonExistingMetadata() throws Exception {
        // Get the metadata
        restMetadataMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingMetadata() throws Exception {
        // Initialize the database
        metadataRepository.save(metadata);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the metadata
        Metadata updatedMetadata = metadataRepository.findById(metadata.getId()).orElseThrow();
        updatedMetadata
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .data(UPDATED_DATA);

        restMetadataMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedMetadata.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(updatedMetadata))
            )
            .andExpect(status().isOk());

        // Validate the Metadata in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertPersistedMetadataToMatchAllProperties(updatedMetadata);
    }

    @Test
    void putNonExistingMetadata() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        metadata.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restMetadataMockMvc
            .perform(
                put(ENTITY_API_URL_ID, metadata.getId()).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(metadata))
            )
            .andExpect(status().isBadRequest());

        // Validate the Metadata in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchMetadata() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        metadata.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restMetadataMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(metadata))
            )
            .andExpect(status().isBadRequest());

        // Validate the Metadata in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamMetadata() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        metadata.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restMetadataMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(metadata)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Metadata in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateMetadataWithPatch() throws Exception {
        // Initialize the database
        metadataRepository.save(metadata);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the metadata using partial update
        Metadata partialUpdatedMetadata = new Metadata();
        partialUpdatedMetadata.setId(metadata.getId());

        partialUpdatedMetadata.modifiedBy(UPDATED_MODIFIED_BY).modifiedDate(UPDATED_MODIFIED_DATE);

        restMetadataMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedMetadata.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedMetadata))
            )
            .andExpect(status().isOk());

        // Validate the Metadata in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertMetadataUpdatableFieldsEquals(createUpdateProxyForBean(partialUpdatedMetadata, metadata), getPersistedMetadata(metadata));
    }

    @Test
    void fullUpdateMetadataWithPatch() throws Exception {
        // Initialize the database
        metadataRepository.save(metadata);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the metadata using partial update
        Metadata partialUpdatedMetadata = new Metadata();
        partialUpdatedMetadata.setId(metadata.getId());

        partialUpdatedMetadata
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .data(UPDATED_DATA);

        restMetadataMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedMetadata.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedMetadata))
            )
            .andExpect(status().isOk());

        // Validate the Metadata in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertMetadataUpdatableFieldsEquals(partialUpdatedMetadata, getPersistedMetadata(partialUpdatedMetadata));
    }

    @Test
    void patchNonExistingMetadata() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        metadata.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restMetadataMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, metadata.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(metadata))
            )
            .andExpect(status().isBadRequest());

        // Validate the Metadata in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchMetadata() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        metadata.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restMetadataMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(metadata))
            )
            .andExpect(status().isBadRequest());

        // Validate the Metadata in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamMetadata() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        metadata.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restMetadataMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(om.writeValueAsBytes(metadata)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Metadata in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteMetadata() throws Exception {
        // Initialize the database
        metadataRepository.save(metadata);

        long databaseSizeBeforeDelete = getRepositoryCount();

        // Delete the metadata
        restMetadataMockMvc
            .perform(delete(ENTITY_API_URL_ID, metadata.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        assertDecrementedRepositoryCount(databaseSizeBeforeDelete);
    }

    protected long getRepositoryCount() {
        return metadataRepository.count();
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

    protected Metadata getPersistedMetadata(Metadata metadata) {
        return metadataRepository.findById(metadata.getId()).orElseThrow();
    }

    protected void assertPersistedMetadataToMatchAllProperties(Metadata expectedMetadata) {
        assertMetadataAllPropertiesEquals(expectedMetadata, getPersistedMetadata(expectedMetadata));
    }

    protected void assertPersistedMetadataToMatchUpdatableProperties(Metadata expectedMetadata) {
        assertMetadataAllUpdatablePropertiesEquals(expectedMetadata, getPersistedMetadata(expectedMetadata));
    }
}
