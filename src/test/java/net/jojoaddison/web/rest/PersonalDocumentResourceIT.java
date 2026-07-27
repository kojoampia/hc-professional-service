package net.jojoaddison.web.rest;

import static net.jojoaddison.domain.PersonalDocumentAsserts.*;
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
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.repository.PersonalDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link PersonalDocumentResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser(authorities = { "ROLE_DOCTOR" })
class PersonalDocumentResourceIT {

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

    private static final String ENTITY_API_URL = "/api/personal-documents";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private MockMvc restPersonalDocumentMockMvc;

    private PersonalDocument personalDocument;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static PersonalDocument createEntity() {
        PersonalDocument personalDocument = new PersonalDocument()
            .name(DEFAULT_NAME)
            .profileId(DEFAULT_PROFILE_ID)
            .data(DEFAULT_DATA)
            .dataContentType(DEFAULT_DATA_CONTENT_TYPE)
            .type(DEFAULT_TYPE)
            .createdDate(DEFAULT_CREATED_DATE)
            .modifiedDate(DEFAULT_MODIFIED_DATE)
            .lastModifiedBy(DEFAULT_LAST_MODIFIED_BY);
        return personalDocument;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static PersonalDocument createUpdatedEntity() {
        PersonalDocument personalDocument = new PersonalDocument()
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);
        return personalDocument;
    }

    @BeforeEach
    public void initTest() {
        personalDocumentRepository.deleteAll();
        personalDocument = createEntity();
    }

    @Test
    void createPersonalDocument() throws Exception {
        long databaseSizeBeforeCreate = getRepositoryCount();
        // Create the PersonalDocument
        var returnedPersonalDocument = om.readValue(
            restPersonalDocumentMockMvc
                .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(personalDocument)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            PersonalDocument.class
        );

        // Validate the PersonalDocument in the database
        assertIncrementedRepositoryCount(databaseSizeBeforeCreate);
        assertPersonalDocumentUpdatableFieldsEquals(returnedPersonalDocument, getPersistedPersonalDocument(returnedPersonalDocument));
    }

    @Test
    void createPersonalDocumentWithExistingId() throws Exception {
        // Create the PersonalDocument with an existing ID
        personalDocument.setId("existing_id");

        long databaseSizeBeforeCreate = getRepositoryCount();

        // An entity with an existing ID cannot be created, so this API call must fail
        restPersonalDocumentMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(personalDocument)))
            .andExpect(status().isBadRequest());

        // Validate the PersonalDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeCreate);
    }

    @Test
    void getAllPersonalDocuments() throws Exception {
        // Initialize the database
        personalDocumentRepository.save(personalDocument);

        // Get all the personalDocumentList
        restPersonalDocumentMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(personalDocument.getId())))
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
    void getPersonalDocument() throws Exception {
        // Initialize the database
        personalDocumentRepository.save(personalDocument);

        // Get the personalDocument
        restPersonalDocumentMockMvc
            .perform(get(ENTITY_API_URL_ID, personalDocument.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(personalDocument.getId()))
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
    void getNonExistingPersonalDocument() throws Exception {
        // Get the personalDocument
        restPersonalDocumentMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingPersonalDocument() throws Exception {
        // Initialize the database
        personalDocumentRepository.save(personalDocument);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the personalDocument
        PersonalDocument updatedPersonalDocument = personalDocumentRepository.findById(personalDocument.getId()).orElseThrow();
        updatedPersonalDocument
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restPersonalDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedPersonalDocument.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(updatedPersonalDocument))
            )
            .andExpect(status().isOk());

        // Validate the PersonalDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertPersistedPersonalDocumentToMatchAllProperties(updatedPersonalDocument);
    }

    @Test
    void putNonExistingPersonalDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        personalDocument.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restPersonalDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, personalDocument.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(personalDocument))
            )
            .andExpect(status().isBadRequest());

        // Validate the PersonalDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchPersonalDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        personalDocument.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restPersonalDocumentMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(personalDocument))
            )
            .andExpect(status().isBadRequest());

        // Validate the PersonalDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamPersonalDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        personalDocument.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restPersonalDocumentMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(personalDocument)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the PersonalDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdatePersonalDocumentWithPatch() throws Exception {
        // Initialize the database
        personalDocumentRepository.save(personalDocument);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the personalDocument using partial update
        PersonalDocument partialUpdatedPersonalDocument = new PersonalDocument();
        partialUpdatedPersonalDocument.setId(personalDocument.getId());

        partialUpdatedPersonalDocument.createdDate(UPDATED_CREATED_DATE).lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restPersonalDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedPersonalDocument.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedPersonalDocument))
            )
            .andExpect(status().isOk());

        // Validate the PersonalDocument in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertPersonalDocumentUpdatableFieldsEquals(
            createUpdateProxyForBean(partialUpdatedPersonalDocument, personalDocument),
            getPersistedPersonalDocument(personalDocument)
        );
    }

    @Test
    void fullUpdatePersonalDocumentWithPatch() throws Exception {
        // Initialize the database
        personalDocumentRepository.save(personalDocument);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the personalDocument using partial update
        PersonalDocument partialUpdatedPersonalDocument = new PersonalDocument();
        partialUpdatedPersonalDocument.setId(personalDocument.getId());

        partialUpdatedPersonalDocument
            .name(UPDATED_NAME)
            .profileId(UPDATED_PROFILE_ID)
            .data(UPDATED_DATA)
            .dataContentType(UPDATED_DATA_CONTENT_TYPE)
            .type(UPDATED_TYPE)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .lastModifiedBy(UPDATED_LAST_MODIFIED_BY);

        restPersonalDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedPersonalDocument.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedPersonalDocument))
            )
            .andExpect(status().isOk());

        // Validate the PersonalDocument in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertPersonalDocumentUpdatableFieldsEquals(
            partialUpdatedPersonalDocument,
            getPersistedPersonalDocument(partialUpdatedPersonalDocument)
        );
    }

    @Test
    void patchNonExistingPersonalDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        personalDocument.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restPersonalDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, personalDocument.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(personalDocument))
            )
            .andExpect(status().isBadRequest());

        // Validate the PersonalDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchPersonalDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        personalDocument.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restPersonalDocumentMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(personalDocument))
            )
            .andExpect(status().isBadRequest());

        // Validate the PersonalDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamPersonalDocument() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        personalDocument.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restPersonalDocumentMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(om.writeValueAsBytes(personalDocument)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the PersonalDocument in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void deletePersonalDocument() throws Exception {
        // Initialize the database
        personalDocumentRepository.save(personalDocument);

        long databaseSizeBeforeDelete = getRepositoryCount();

        // Delete the personalDocument
        restPersonalDocumentMockMvc
            .perform(delete(ENTITY_API_URL_ID, personalDocument.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        assertDecrementedRepositoryCount(databaseSizeBeforeDelete);
    }

    protected long getRepositoryCount() {
        return personalDocumentRepository.count();
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

    protected PersonalDocument getPersistedPersonalDocument(PersonalDocument personalDocument) {
        return personalDocumentRepository.findById(personalDocument.getId()).orElseThrow();
    }

    protected void assertPersistedPersonalDocumentToMatchAllProperties(PersonalDocument expectedPersonalDocument) {
        assertPersonalDocumentAllPropertiesEquals(expectedPersonalDocument, getPersistedPersonalDocument(expectedPersonalDocument));
    }

    protected void assertPersistedPersonalDocumentToMatchUpdatableProperties(PersonalDocument expectedPersonalDocument) {
        assertPersonalDocumentAllUpdatablePropertiesEquals(
            expectedPersonalDocument,
            getPersistedPersonalDocument(expectedPersonalDocument)
        );
    }
}
