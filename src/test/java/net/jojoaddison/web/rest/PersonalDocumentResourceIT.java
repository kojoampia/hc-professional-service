package net.jojoaddison.web.rest;

import static net.jojoaddison.domain.PersonalDocumentAsserts.*;
import static net.jojoaddison.web.rest.TestUtil.createUpdateProxyForBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link PersonalDocumentResource} REST controller — <b>its writes, which
 * are the only verbs it maps</b>.
 *
 * <h2>⛔ The three read tests are gone with the three {@code GET} mappings (S1, backlog.md row 226)</h2>
 *
 * <p>{@code getAllPersonalDocuments}, {@code getPersonalDocument} and
 * {@code getNonExistingPersonalDocument} stood here, and the first two asserted — among other fields —
 * {@code $.[*].data} and {@code $.data} equal to the base64 of the stored bytes. <b>That is the
 * defect, written down as an expectation.</b> The resource carried no {@code @PreAuthorize} anywhere,
 * so those reads answered any authenticated caller with every document in the collection, bytes
 * included; the only thing that ever exercised them was this class, and this class asserted the 200 was
 * correct. A green suite was part of S1, which is why they are deleted rather than re-pointed at
 * something.
 *
 * <p><b>What is no longer covered here, plainly.</b> Nothing in this class now reads a document back
 * over HTTP — the {@code PUT}/{@code PATCH} cases verify through {@code personalDocumentRepository}
 * instead, as they always did, and no test asserts the JSON projection of a {@code PersonalDocument} on
 * this path, because there is no {@code GET} on this path to project it. The reads a product surface
 * makes live on two other resources and are covered by their own ITs:
 * {@link OwnPersonalDocumentResource}'s own list ({@code data} nulled) and {@code /{id}/content}
 * (owner-or-reviewer) in {@code OwnPersonalDocumentResourceIT}, and a reviewer's document list,
 * {@code GET /api/professional-application/&#123;id&#125;/documents} ({@code assertAdminOrOwner},
 * {@code data} nulled), in {@code ProfessionalApplicationResourceIT}. The <em>absence</em> of the
 * plural {@code GET}s is held by {@code ClinicalAuthorityMatrixIT}, which asserts the statuses measured
 * for them — 405 on the collection and on {@code /{id}}, 404 on {@code /profile/{profileId}} — for an
 * applicant, a read-only discipline and an administrator alike.
 *
 * <p>⚠ <b>"No {@code GET}" is not "no read", and this class is where that would mislead most.</b> The
 * surviving {@code PATCH} returns the merged row, so a merge-patch body carrying only {@code id}
 * answers 200 with the whole document, bytes included, to any of the six {@code CLINICAL_MUTATION}
 * authorities with no ownership check — backlog.md row 227. {@code partialUpdatePersonalDocumentWithPatch}
 * and {@code fullUpdatePersonalDocumentWithPatch} below run as {@code ROLE_DOCTOR} and <em>do</em>
 * receive that body; they assert the persisted row rather than the response, and <b>no case here
 * asserts the echo</b>, deliberately: a test pinning a defect in place is worse than a row naming it.
 *
 * <p>⚠ <b>This class is still the only description of the write surface</b>, and that surface is
 * deliberately unchanged: it is the admin data-maintenance path for a document, including the
 * whole-document {@code PUT} that un-archives a row by omitting {@code supersededAt} — backlog.md item
 * 46, not fixed here and not made worse here. Row 227 is where the {@code PATCH} echo and that
 * {@code PUT}'s blanking of {@code data} get decided together.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockGatewayUser(authorities = { "ROLE_DOCTOR" })
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

    // The three generated read tests stood here. See the class javadoc: two of them asserted the
    // document bytes on the wire, which was S1 stated as an expectation. They are deleted because the
    // handlers are, and the absence is asserted in ClinicalAuthorityMatrixIT rather than here — a
    // status-only case in this class would read as a gap in the generated CRUD coverage, where there
    // it reads as the security claim it is.

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
