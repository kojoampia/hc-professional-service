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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.HCDocument;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link DocumentResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class DocumentResourceIT {

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

    private static final String ENTITY_API_URL = "/api/documents";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private MockMvc restDocumentMockMvc;

    private HCDocument document;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static HCDocument createEntity() {
        HCDocument document = new HCDocument()
            .name(DEFAULT_NAME)
            .profileId(DEFAULT_PROFILE_ID)
            .data(DEFAULT_DATA)
            .dataContentType(DEFAULT_DATA_CONTENT_TYPE)
            .type(DEFAULT_TYPE)
            .createdDate(DEFAULT_CREATED_DATE)
            .modifiedDate(DEFAULT_MODIFIED_DATE)
            .lastModifiedBy(DEFAULT_LAST_MODIFIED_BY);
        return document;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static HCDocument createUpdatedEntity() {
        HCDocument document = new HCDocument()
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);
        return document;
    }

    @BeforeEach
    public void initTest() {
        documentRepository.deleteAll();
        document = createEntity();
    }

    @Test
    void createDocument() throws Exception {
        int databaseSizeBeforeCreate = documentRepository.findAll().size();
        // Create the Document
        restDocumentMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(document)))
            .andExpect(status().isCreated());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeCreate + 1);
        HCDocument testDocument = documentList.get(documentList.size() - 1);
        assertThat(testDocument.getName()).isEqualTo(DEFAULT_NAME);
        assertThat(testDocument.getProfileId()).isEqualTo(DEFAULT_PROFILE_ID);
        assertThat(testDocument.getData()).isEqualTo(DEFAULT_DATA);
        assertThat(testDocument.getDataContentType()).isEqualTo(DEFAULT_DATA_CONTENT_TYPE);
        assertThat(testDocument.getType()).isEqualTo(DEFAULT_TYPE);
        assertThat(testDocument.getCreatedDate()).isEqualTo(DEFAULT_CREATED_DATE);
        assertThat(testDocument.getModifiedDate()).isEqualTo(DEFAULT_MODIFIED_DATE);
        assertThat(testDocument.getLastModifiedBy()).isEqualTo(DEFAULT_LAST_MODIFIED_BY);
    }

    @Test
    void createDocumentWithExistingId() throws Exception {
        // Create the Document with an existing ID
        document.setId("existing_id");

        int databaseSizeBeforeCreate = documentRepository.findAll().size();

        // An entity with an existing ID cannot be created, so this API call must fail
        restDocumentMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(document)))
            .andExpect(status().isBadRequest());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    void getAllDocuments() throws Exception {
        // Initialize the database
        documentRepository.save(document);

        // Get all the documentList
        restDocumentMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(document.getId())))
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
    void getDocument() throws Exception {
        // Initialize the database
        documentRepository.save(document);

        // Get the document
        restDocumentMockMvc
            .perform(get(ENTITY_API_URL_ID, document.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(document.getId()))
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
    void getNonExistingDocument() throws Exception {
        // Get the document
        restDocumentMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingDocument() throws Exception {
        // Initialize the database
        documentRepository.save(document);

        int databaseSizeBeforeUpdate = documentRepository.findAll().size();

        // Update the document
        HCDocument updatedDocument = documentRepository.findById(document.getId()).orElseThrow();
        updatedDocument
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedDocument.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(updatedDocument))
            )
            .andExpect(status().isOk());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
        HCDocument testDocument = documentList.get(documentList.size() - 1);
        assertThat(testDocument.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testDocument.getProfileId()).isEqualTo(UPDATED_PROFILE_ID);
        assertThat(testDocument.getData()).isEqualTo(UPDATED_DATA);
        assertThat(testDocument.getDataContentType()).isEqualTo(UPDATED_DATA_CONTENT_TYPE);
        assertThat(testDocument.getType()).isEqualTo(UPDATED_TYPE);
        assertThat(testDocument.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testDocument.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testDocument.getLastModifiedBy()).isEqualTo(UPDATED_LAST_MODIFIED_BY);
    }

    @Test
    void putNonExistingDocument() throws Exception {
        int databaseSizeBeforeUpdate = documentRepository.findAll().size();
        document.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, document.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(document))
            )
            .andExpect(status().isBadRequest());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchDocument() throws Exception {
        int databaseSizeBeforeUpdate = documentRepository.findAll().size();
        document.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(document))
            )
            .andExpect(status().isBadRequest());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamDocument() throws Exception {
        int databaseSizeBeforeUpdate = documentRepository.findAll().size();
        document.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restDocumentMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(document)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateDocumentWithPatch() throws Exception {
        // Initialize the database
        documentRepository.save(document);

        int databaseSizeBeforeUpdate = documentRepository.findAll().size();

        // Update the document using partial update
        HCDocument partialUpdatedDocument = new HCDocument();
        partialUpdatedDocument.setId(document.getId());

        partialUpdatedDocument.name(UPDATED_NAME).type(UPDATED_TYPE).lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedDocument.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedDocument))
            )
            .andExpect(status().isOk());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
        HCDocument testDocument = documentList.get(documentList.size() - 1);
        assertThat(testDocument.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testDocument.getProfileId()).isEqualTo(DEFAULT_PROFILE_ID);
        assertThat(testDocument.getData()).isEqualTo(DEFAULT_DATA);
        assertThat(testDocument.getDataContentType()).isEqualTo(DEFAULT_DATA_CONTENT_TYPE);
        assertThat(testDocument.getType()).isEqualTo(UPDATED_TYPE);
        assertThat(testDocument.getCreatedDate()).isEqualTo(DEFAULT_CREATED_DATE);
        assertThat(testDocument.getModifiedDate()).isEqualTo(DEFAULT_MODIFIED_DATE);
        assertThat(testDocument.getLastModifiedBy()).isEqualTo(UPDATED_LAST_MODIFIED_BY);
    }

    @Test
    void fullUpdateDocumentWithPatch() throws Exception {
        // Initialize the database
        documentRepository.save(document);

        int databaseSizeBeforeUpdate = documentRepository.findAll().size();

        // Update the document using partial update
        HCDocument partialUpdatedDocument = new HCDocument();
        partialUpdatedDocument.setId(document.getId());

        partialUpdatedDocument
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedDocument.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedDocument))
            )
            .andExpect(status().isOk());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
        HCDocument testDocument = documentList.get(documentList.size() - 1);
        assertThat(testDocument.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testDocument.getProfileId()).isEqualTo(UPDATED_PROFILE_ID);
        assertThat(testDocument.getData()).isEqualTo(UPDATED_DATA);
        assertThat(testDocument.getDataContentType()).isEqualTo(UPDATED_DATA_CONTENT_TYPE);
        assertThat(testDocument.getType()).isEqualTo(UPDATED_TYPE);
        assertThat(testDocument.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testDocument.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testDocument.getLastModifiedBy()).isEqualTo(UPDATED_LAST_MODIFIED_BY);
    }

    @Test
    void patchNonExistingDocument() throws Exception {
        int databaseSizeBeforeUpdate = documentRepository.findAll().size();
        document.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, document.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(document))
            )
            .andExpect(status().isBadRequest());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchDocument() throws Exception {
        int databaseSizeBeforeUpdate = documentRepository.findAll().size();
        document.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(document))
            )
            .andExpect(status().isBadRequest());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamDocument() throws Exception {
        int databaseSizeBeforeUpdate = documentRepository.findAll().size();
        document.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restDocumentMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(TestUtil.convertObjectToJsonBytes(document)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Document in the database
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteDocument() throws Exception {
        // Initialize the database
        documentRepository.save(document);

        int databaseSizeBeforeDelete = documentRepository.findAll().size();

        // Delete the document
        restDocumentMockMvc
            .perform(delete(ENTITY_API_URL_ID, document.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        List<HCDocument> documentList = documentRepository.findAll();
        assertThat(documentList).hasSize(databaseSizeBeforeDelete - 1);
    }
}
