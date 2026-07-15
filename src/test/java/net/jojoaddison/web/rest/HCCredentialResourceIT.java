package net.jojoaddison.web.rest;

import static net.jojoaddison.domain.HCCredentialAsserts.*;
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
import net.jojoaddison.domain.HCCredential;
import net.jojoaddison.repository.HCCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link HCCredentialResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class HCCredentialResourceIT {

    private static final String DEFAULT_EMAIL = "AAAAAAAAAA";
    private static final String UPDATED_EMAIL = "BBBBBBBBBB";

    private static final String DEFAULT_PHONE_NUMBER = "AAAAAAAAAA";
    private static final String UPDATED_PHONE_NUMBER = "BBBBBBBBBB";

    private static final String DEFAULT_PASSWORD = "AAAAAAAAAA";
    private static final String UPDATED_PASSWORD = "BBBBBBBBBB";

    private static final String DEFAULT_ROLE = "AAAAAAAAAA";
    private static final String UPDATED_ROLE = "BBBBBBBBBB";

    private static final LocalDate DEFAULT_CREATED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_CREATED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final Boolean DEFAULT_ACTIVE = false;
    private static final Boolean UPDATED_ACTIVE = true;

    private static final LocalDate DEFAULT_MODIFIED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_MODIFIED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final String ENTITY_API_URL = "/api/hc-credentials";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private HCCredentialRepository hCCredentialRepository;

    @Autowired
    private MockMvc restHCCredentialMockMvc;

    private HCCredential hCCredential;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static HCCredential createEntity() {
        HCCredential hCCredential = new HCCredential()
            .email(DEFAULT_EMAIL)
            .phoneNumber(DEFAULT_PHONE_NUMBER)
            .password(DEFAULT_PASSWORD)
            .role(DEFAULT_ROLE)
            .createdDate(DEFAULT_CREATED_DATE)
            .active(DEFAULT_ACTIVE)
            .modifiedDate(DEFAULT_MODIFIED_DATE);
        return hCCredential;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static HCCredential createUpdatedEntity() {
        HCCredential hCCredential = new HCCredential()
            .email(UPDATED_EMAIL)
            .phoneNumber(UPDATED_PHONE_NUMBER)
            .password(UPDATED_PASSWORD)
            .role(UPDATED_ROLE)
            .createdDate(UPDATED_CREATED_DATE)
            .active(UPDATED_ACTIVE)
            .modifiedDate(UPDATED_MODIFIED_DATE);
        return hCCredential;
    }

    @BeforeEach
    public void initTest() {
        hCCredentialRepository.deleteAll();
        hCCredential = createEntity();
    }

    @Test
    void createHCCredential() throws Exception {
        long databaseSizeBeforeCreate = getRepositoryCount();
        // Create the HCCredential
        var returnedHCCredential = om.readValue(
            restHCCredentialMockMvc
                .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCCredential)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            HCCredential.class
        );

        // Validate the HCCredential in the database
        assertIncrementedRepositoryCount(databaseSizeBeforeCreate);
        assertHCCredentialUpdatableFieldsEquals(returnedHCCredential, getPersistedHCCredential(returnedHCCredential));
    }

    @Test
    void createHCCredentialWithExistingId() throws Exception {
        // Create the HCCredential with an existing ID
        hCCredential.setId("existing_id");

        long databaseSizeBeforeCreate = getRepositoryCount();

        // An entity with an existing ID cannot be created, so this API call must fail
        restHCCredentialMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCCredential)))
            .andExpect(status().isBadRequest());

        // Validate the HCCredential in the database
        assertSameRepositoryCount(databaseSizeBeforeCreate);
    }

    @Test
    void getAllHCCredentials() throws Exception {
        // Initialize the database
        hCCredentialRepository.save(hCCredential);

        // Get all the hCCredentialList
        restHCCredentialMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(hCCredential.getId())))
            .andExpect(jsonPath("$.[*].email").value(hasItem(DEFAULT_EMAIL)))
            .andExpect(jsonPath("$.[*].phoneNumber").value(hasItem(DEFAULT_PHONE_NUMBER)))
            .andExpect(jsonPath("$.[*].password").value(hasItem(DEFAULT_PASSWORD)))
            .andExpect(jsonPath("$.[*].role").value(hasItem(DEFAULT_ROLE)))
            .andExpect(jsonPath("$.[*].createdDate").value(hasItem(DEFAULT_CREATED_DATE.toString())))
            .andExpect(jsonPath("$.[*].active").value(hasItem(DEFAULT_ACTIVE.booleanValue())))
            .andExpect(jsonPath("$.[*].modifiedDate").value(hasItem(DEFAULT_MODIFIED_DATE.toString())));
    }

    @Test
    void getHCCredential() throws Exception {
        // Initialize the database
        hCCredentialRepository.save(hCCredential);

        // Get the hCCredential
        restHCCredentialMockMvc
            .perform(get(ENTITY_API_URL_ID, hCCredential.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(hCCredential.getId()))
            .andExpect(jsonPath("$.email").value(DEFAULT_EMAIL))
            .andExpect(jsonPath("$.phoneNumber").value(DEFAULT_PHONE_NUMBER))
            .andExpect(jsonPath("$.password").value(DEFAULT_PASSWORD))
            .andExpect(jsonPath("$.role").value(DEFAULT_ROLE))
            .andExpect(jsonPath("$.createdDate").value(DEFAULT_CREATED_DATE.toString()))
            .andExpect(jsonPath("$.active").value(DEFAULT_ACTIVE.booleanValue()))
            .andExpect(jsonPath("$.modifiedDate").value(DEFAULT_MODIFIED_DATE.toString()));
    }

    @Test
    void getNonExistingHCCredential() throws Exception {
        // Get the hCCredential
        restHCCredentialMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingHCCredential() throws Exception {
        // Initialize the database
        hCCredentialRepository.save(hCCredential);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCCredential
        HCCredential updatedHCCredential = hCCredentialRepository.findById(hCCredential.getId()).orElseThrow();
        updatedHCCredential
            .email(UPDATED_EMAIL)
            .phoneNumber(UPDATED_PHONE_NUMBER)
            .password(UPDATED_PASSWORD)
            .role(UPDATED_ROLE)
            .createdDate(UPDATED_CREATED_DATE)
            .active(UPDATED_ACTIVE)
            .modifiedDate(UPDATED_MODIFIED_DATE);

        restHCCredentialMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedHCCredential.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(updatedHCCredential))
            )
            .andExpect(status().isOk());

        // Validate the HCCredential in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertPersistedHCCredentialToMatchAllProperties(updatedHCCredential);
    }

    @Test
    void putNonExistingHCCredential() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCCredential.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restHCCredentialMockMvc
            .perform(
                put(ENTITY_API_URL_ID, hCCredential.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(hCCredential))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCCredential in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchHCCredential() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCCredential.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCCredentialMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(hCCredential))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCCredential in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamHCCredential() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCCredential.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCCredentialMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(hCCredential)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the HCCredential in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateHCCredentialWithPatch() throws Exception {
        // Initialize the database
        hCCredentialRepository.save(hCCredential);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCCredential using partial update
        HCCredential partialUpdatedHCCredential = new HCCredential();
        partialUpdatedHCCredential.setId(hCCredential.getId());

        partialUpdatedHCCredential
            .email(UPDATED_EMAIL)
            .phoneNumber(UPDATED_PHONE_NUMBER)
            .password(UPDATED_PASSWORD)
            .role(UPDATED_ROLE)
            .createdDate(UPDATED_CREATED_DATE)
            .active(UPDATED_ACTIVE)
            .modifiedDate(UPDATED_MODIFIED_DATE);

        restHCCredentialMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedHCCredential.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedHCCredential))
            )
            .andExpect(status().isOk());

        // Validate the HCCredential in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertHCCredentialUpdatableFieldsEquals(
            createUpdateProxyForBean(partialUpdatedHCCredential, hCCredential),
            getPersistedHCCredential(hCCredential)
        );
    }

    @Test
    void fullUpdateHCCredentialWithPatch() throws Exception {
        // Initialize the database
        hCCredentialRepository.save(hCCredential);

        long databaseSizeBeforeUpdate = getRepositoryCount();

        // Update the hCCredential using partial update
        HCCredential partialUpdatedHCCredential = new HCCredential();
        partialUpdatedHCCredential.setId(hCCredential.getId());

        partialUpdatedHCCredential
            .email(UPDATED_EMAIL)
            .phoneNumber(UPDATED_PHONE_NUMBER)
            .password(UPDATED_PASSWORD)
            .role(UPDATED_ROLE)
            .createdDate(UPDATED_CREATED_DATE)
            .active(UPDATED_ACTIVE)
            .modifiedDate(UPDATED_MODIFIED_DATE);

        restHCCredentialMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedHCCredential.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedHCCredential))
            )
            .andExpect(status().isOk());

        // Validate the HCCredential in the database

        assertSameRepositoryCount(databaseSizeBeforeUpdate);
        assertHCCredentialUpdatableFieldsEquals(partialUpdatedHCCredential, getPersistedHCCredential(partialUpdatedHCCredential));
    }

    @Test
    void patchNonExistingHCCredential() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCCredential.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restHCCredentialMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, hCCredential.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(hCCredential))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCCredential in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchHCCredential() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCCredential.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCCredentialMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(hCCredential))
            )
            .andExpect(status().isBadRequest());

        // Validate the HCCredential in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamHCCredential() throws Exception {
        long databaseSizeBeforeUpdate = getRepositoryCount();
        hCCredential.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restHCCredentialMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(om.writeValueAsBytes(hCCredential)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the HCCredential in the database
        assertSameRepositoryCount(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteHCCredential() throws Exception {
        // Initialize the database
        hCCredentialRepository.save(hCCredential);

        long databaseSizeBeforeDelete = getRepositoryCount();

        // Delete the hCCredential
        restHCCredentialMockMvc
            .perform(delete(ENTITY_API_URL_ID, hCCredential.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        assertDecrementedRepositoryCount(databaseSizeBeforeDelete);
    }

    protected long getRepositoryCount() {
        return hCCredentialRepository.count();
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

    protected HCCredential getPersistedHCCredential(HCCredential hCCredential) {
        return hCCredentialRepository.findById(hCCredential.getId()).orElseThrow();
    }

    protected void assertPersistedHCCredentialToMatchAllProperties(HCCredential expectedHCCredential) {
        assertHCCredentialAllPropertiesEquals(expectedHCCredential, getPersistedHCCredential(expectedHCCredential));
    }

    protected void assertPersistedHCCredentialToMatchUpdatableProperties(HCCredential expectedHCCredential) {
        assertHCCredentialAllUpdatablePropertiesEquals(expectedHCCredential, getPersistedHCCredential(expectedHCCredential));
    }
}
