package net.jojoaddison.web.rest;

import static net.jojoaddison.domain.IDocumentAsserts.*;
import static net.jojoaddison.web.rest.TestUtil.createUpdateProxyForBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.IDocument;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.repository.IDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link IDocumentResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class IDocumentResourceIT {

    private static final String DEFAULT_NAME = "AAAAAAAAAA";
    private static final String UPDATED_NAME = "BBBBBBBBBB";

    private static final String DEFAULT_PROFILE_ID = "AAAAAAAAAA";
    private static final String UPDATED_PROFILE_ID = "BBBBBBBBBB";

    private static final byte[] DEFAULT_DATA = TestUtil.createByteArray(1, "0");
    private static final byte[] UPDATED_DATA = TestUtil.createByteArray(1, "1");
    private static final String DEFAULT_DATA_CONTENT_TYPE = "image/jpg";
    private static final String UPDATED_DATA_CONTENT_TYPE = "image/png";

    private static final DocumentType DEFAULT_TYPE = DocumentType.PASSPORT;
    private static final DocumentType UPDATED_TYPE = DocumentType.CERTIFICATE;

    private static final LocalDate DEFAULT_CREATED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_CREATED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final LocalDate DEFAULT_MODIFIED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_MODIFIED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final String DEFAULT_LAST_MODIFIED_BY = "AAAAAAAAAA";
    private static final String UPDATED_LAST_MODIFIED_BY = "BBBBBBBBBB";

    private static final String ENTITY_API_URL = "/api/hc-documents";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private IDocumentRepository iDocumentRepository;

    @Autowired
    private MockMvc restIDocumentMockMvc;

    private IDocument hCDocument;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static IDocument createEntity() {
        IDocument hCDocument = new IDocument()
            .name(DEFAULT_NAME)
            .profileId(DEFAULT_PROFILE_ID)
            .data(DEFAULT_DATA)
            .dataContentType(DEFAULT_DATA_CONTENT_TYPE)
            .type(DEFAULT_TYPE)
            .createdDate(DEFAULT_CREATED_DATE)
            .modifiedDate(DEFAULT_MODIFIED_DATE)
            .lastModifiedBy(DEFAULT_LAST_MODIFIED_BY);
        return hCDocument;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static IDocument createUpdatedEntity() {
        IDocument hCDocument = new IDocument()
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);
        return hCDocument;
    }

    @BeforeEach
    public void initTest() {
        iDocumentRepository.deleteAll();
        hCDocument = createEntity();
    }

    @Test
    void createHCDocument() throws Exception {
        long databaseSizeBeforeCreate = getRepositoryCount();
        // Create the HCDocument
        var returnedHCDocument = om.readValue(
            restIDocumentMockMvc
                .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCDocument)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            IDocument.class
        );

        // Validate the HCDocument in the database
        assertIncrementedRepositoryCount(databaseSizeBeforeCreate);
        assertHCDocumentUpdatableFieldsEquals(returnedHCDocument, getPersistedHCDocument(returnedHCDocument));
    }

    @Test
    void createHCDocumentWithExistingId() throws Exception {
        // Create the HCDocument with an existing ID
        hCDocument.setId("existing_id");

        long databaseSizeBeforeCreate = getRepositoryCount();

        // An entity with an existing ID cannot be created, so this API call must fail
        restIDocumentMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCDocument)))
            .andExpect(status().isBadRequest());

        // Validate the HCDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeCreate);
    }

    @Test
    void getAllHCDocuments() throws Exception {
        // Initialize the database
        iDocumentRepository.save(hCDocument);

        // Get all the hCDocumentList
        restIDocumentMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(hCDocument.getId())))
            .andExpect(jsonPath("$.[*].name").value(hasItem(DEFAULT_NAME)))
            .andExpect(jsonPath("$.[*].profileId").value(hasItem(DEFAULT_PROFILE_ID)))
            .andExpect(jsonPath("$.[*].dataContentType").value(hasItem(DEFAULT_DATA_CONTENT_TYPE)))
            .andExpect(jsonPath("$.[*].data").value(hasItem(Base64.getEncoder().encodeToString(DEFAULT_DATA))))
            .andExpect(jsonPath("$.[*].type").value(hasItem(DEFAULT_TYPE.toString())))
            .andExpect(jsonPath("$.[*].createdDate").value(hasItem(DEFAULT_CREATED_DATE.toString())))
            .andExpect(jsonPath("$.[*].modifiedDate").value(hasItem(DEFAULT_MODIFIED_DATE.toString())))
            .andExpect(jsonPath("$.[*].lastModifiedBy").value(hasItem(DEFAULT_LAST_MODIFIED_BY)));
    }

    @Test
    void getHCDocument() throws Exception {
        // Initialize the database
        iDocumentRepository.save(hCDocument);

        // Get the hCDocument
        restIDocumentMockMvc
            .perform(get(ENTITY_API_URL_ID, hCDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(hCDocument.getId()))
            .andExpect(jsonPath("$.name").value(DEFAULT_NAME))
            .andExpect(jsonPath("$.profileId").value(DEFAULT_PROFILE_ID))
            .andExpect(jsonPath("$.dataContentType").value(DEFAULT_DATA_CONTENT_TYPE))
            .andExpect(jsonPath("$.data").value(Base64.getEncoder().encodeToString(DEFAULT_DATA)))
            .andExpect(jsonPath("$.type").value(DEFAULT_TYPE.toString()))
            .andExpect(jsonPath("$.createdDate").value(DEFAULT_CREATED_DATE.toString()))
            .andExpect(jsonPath("$.modifiedDate").value(DEFAULT_MODIFIED_DATE.toString()))
            .andExpect(jsonPath("$.lastModifiedBy").value(DEFAULT_LAST_MODIFIED_BY));
    }

    @Test
    void getNonExistingHCDocument() throws Exception {
        // Get the hCDocument
        restIDocumentMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingHCDocument() throws Exception {
        // Initialize the database
        iDocumentRepository.save(hCDocument);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCDocument
        IDocument updatedHCDocument = iDocumentRepository.findById(hCDocument.getId()).orElseThrow();
        updatedHCDocument
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restIDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedHCDocument.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(updatedHCDocument))
            )
            .andExpect(status().isOk());

        // Validate the HCDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertPersistedHCDocumentToMatchAllProperties(updatedHCDocument);
    }

    @Test
    void putNonExistingHCDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCDocument.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restIDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, hCDocument.getId()).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCDocument))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchHCDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCDocument.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restIDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(hCDocument))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamHCDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCDocument.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restIDocumentMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCDocument)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the HCDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateHCDocumentWithPatch() throws Exception {
        // Initialize the database
        iDocumentRepository.save(hCDocument);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCDocument using partial update
        IDocument partialUpdatedHCDocument = new IDocument();
        partialUpdatedHCDocument.setId(hCDocument.getId());

        partialUpdatedHCDocument.createdDate(UPDATED_CREATED_DATE).lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restIDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedHCDocument.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedHCDocument))
            )
            .andExpect(status().isOk());

        // Validate the HCDocument in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertHCDocumentUpdatableFieldsEquals(
            createUpdateProxyForBean(partialUpdatedHCDocument, hCDocument),
            getPersistedHCDocument(hCDocument)
        );
    }

    @Test
    void fullUpdateHCDocumentWithPatch() throws Exception {
        // Initialize the database
        iDocumentRepository.save(hCDocument);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCDocument using partial update
        IDocument partialUpdatedHCDocument = new IDocument();
        partialUpdatedHCDocument.setId(hCDocument.getId());

        partialUpdatedHCDocument
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restIDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedHCDocument.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedHCDocument))
            )
            .andExpect(status().isOk());

        // Validate the HCDocument in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertHCDocumentUpdatableFieldsEquals(partialUpdatedHCDocument, getPersistedHCDocument(partialUpdatedHCDocument));
    }

    @Test
    void patchNonExistingHCDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCDocument.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restIDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, hCDocument.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(hCDocument))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchHCDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCDocument.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restIDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(hCDocument))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamHCDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCDocument.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restIDocumentMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(om.writeValueAsBytes(hCDocument)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the HCDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteHCDocument() throws Exception {
        // Initialize the database
        iDocumentRepository.save(hCDocument);

        long databaseSizeBeforeDelete = getRepositoryCount();

        // Delete the hCDocument
        restIDocumentMockMvc
            .perform(delete(ENTITY_API_URL_ID, hCDocument.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        assertDecrementedRepositoryCount(databaseSizeBeforeDelete);
    }

    protected long getRepositoryCount() {
        return iDocumentRepository.count();
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

    protected IDocument getPersistedHCDocument(IDocument hCDocument) {
        return iDocumentRepository.findById(hCDocument.getId()).orElseThrow();
    }

    protected void assertPersistedHCDocumentToMatchAllProperties(IDocument expectedHCDocument) {
        assertHCDocumentAllPropertiesEquals(expectedHCDocument, getPersistedHCDocument(expectedHCDocument));
    }

    protected void assertPersistedHCDocumentToMatchUpdatableProperties(IDocument expectedHCDocument) {
        assertHCDocumentAllUpdatablePropertiesEquals(expectedHCDocument, getPersistedHCDocument(expectedHCDocument));
    }
}
