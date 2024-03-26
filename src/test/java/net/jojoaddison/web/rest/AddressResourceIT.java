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
import java.util.List;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Address;
import net.jojoaddison.repository.AddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link AddressResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class AddressResourceIT {

    private static final String DEFAULT_DIGITAL_ADDRESS = "AAAAAAAAAA";
    private static final String UPDATED_DIGITAL_ADDRESS = "BBBBBBBBBB";

    private static final String DEFAULT_STREET_ADDRESS = "AAAAAAAAAA";
    private static final String UPDATED_STREET_ADDRESS = "BBBBBBBBBB";

    private static final String DEFAULT_AREA_CODE = "AAAAAAAAAA";
    private static final String UPDATED_AREA_CODE = "BBBBBBBBBB";

    private static final String DEFAULT_TOWN = "AAAAAAAAAA";
    private static final String UPDATED_TOWN = "BBBBBBBBBB";

    private static final String DEFAULT_CITY = "AAAAAAAAAA";
    private static final String UPDATED_CITY = "BBBBBBBBBB";

    private static final String DEFAULT_DISTRICT = "AAAAAAAAAA";
    private static final String UPDATED_DISTRICT = "BBBBBBBBBB";

    private static final String DEFAULT_STATE = "AAAAAAAAAA";
    private static final String UPDATED_STATE = "BBBBBBBBBB";

    private static final String DEFAULT_REGION = "AAAAAAAAAA";
    private static final String UPDATED_REGION = "BBBBBBBBBB";

    private static final String DEFAULT_COUNTRY = "AAAAAAAAAA";
    private static final String UPDATED_COUNTRY = "BBBBBBBBBB";

    private static final LocalDate DEFAULT_CREATED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_CREATED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final LocalDate DEFAULT_MODIFIED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_MODIFIED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final String DEFAULT_CREATED_BY = "AAAAAAAAAA";
    private static final String UPDATED_CREATED_BY = "BBBBBBBBBB";

    private static final String DEFAULT_MODIFIED_BY = "AAAAAAAAAA";
    private static final String UPDATED_MODIFIED_BY = "BBBBBBBBBB";

    private static final String ENTITY_API_URL = "/api/addresses";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";
    private static final String ENTITY_SEARCH_API_URL = "/api/addresses/_search";

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private MockMvc restAddressMockMvc;

    private Address address;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Address createEntity() {
        Address address = new Address()
            .digitalAddress(DEFAULT_DIGITAL_ADDRESS)
            .streetAddress(DEFAULT_STREET_ADDRESS)
            .areaCode(DEFAULT_AREA_CODE)
            .town(DEFAULT_TOWN)
            .city(DEFAULT_CITY)
            .district(DEFAULT_DISTRICT)
            .state(DEFAULT_STATE)
            .region(DEFAULT_REGION)
            .country(DEFAULT_COUNTRY)
            .createdDate(DEFAULT_CREATED_DATE)
            .modifiedDate(DEFAULT_MODIFIED_DATE)
            .createdBy(DEFAULT_CREATED_BY)
            .modifiedBy(DEFAULT_MODIFIED_BY);
        return address;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Address createUpdatedEntity() {
        Address address = new Address()
            .digitalAddress(UPDATED_DIGITAL_ADDRESS)
            .streetAddress(UPDATED_STREET_ADDRESS)
            .areaCode(UPDATED_AREA_CODE)
            .town(UPDATED_TOWN)
            .city(UPDATED_CITY)
            .district(UPDATED_DISTRICT)
            .state(UPDATED_STATE)
            .region(UPDATED_REGION)
            .country(UPDATED_COUNTRY)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);
        return address;
    }

    @BeforeEach
    public void initTest() {
        addressRepository.deleteAll();
        address = createEntity();
    }

    @Test
    void createAddress() throws Exception {
        int databaseSizeBeforeCreate = addressRepository.findAll().size();
        // Create the Address
        restAddressMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(address)))
            .andExpect(status().isCreated());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeCreate + 1);
        Address testAddress = addressList.get(addressList.size() - 1);
        assertThat(testAddress.getDigitalAddress()).isEqualTo(DEFAULT_DIGITAL_ADDRESS);
        assertThat(testAddress.getStreetAddress()).isEqualTo(DEFAULT_STREET_ADDRESS);
        assertThat(testAddress.getAreaCode()).isEqualTo(DEFAULT_AREA_CODE);
        assertThat(testAddress.getTown()).isEqualTo(DEFAULT_TOWN);
        assertThat(testAddress.getCity()).isEqualTo(DEFAULT_CITY);
        assertThat(testAddress.getDistrict()).isEqualTo(DEFAULT_DISTRICT);
        assertThat(testAddress.getState()).isEqualTo(DEFAULT_STATE);
        assertThat(testAddress.getRegion()).isEqualTo(DEFAULT_REGION);
        assertThat(testAddress.getCountry()).isEqualTo(DEFAULT_COUNTRY);
        assertThat(testAddress.getCreatedDate()).isEqualTo(DEFAULT_CREATED_DATE);
        assertThat(testAddress.getModifiedDate()).isEqualTo(DEFAULT_MODIFIED_DATE);
        assertThat(testAddress.getCreatedBy()).isEqualTo(DEFAULT_CREATED_BY);
        assertThat(testAddress.getModifiedBy()).isEqualTo(DEFAULT_MODIFIED_BY);
    }

    @Test
    void createAddressWithExistingId() throws Exception {
        // Create the Address with an existing ID
        address.setId("existing_id");

        int databaseSizeBeforeCreate = addressRepository.findAll().size();

        // An entity with an existing ID cannot be created, so this API call must fail
        restAddressMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(address)))
            .andExpect(status().isBadRequest());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    void getAllAddresses() throws Exception {
        // Initialize the database
        addressRepository.save(address);

        // Get all the addressList
        restAddressMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(address.getId())))
            .andExpect(jsonPath("$.[*].digitalAddress").value(hasItem(DEFAULT_DIGITAL_ADDRESS)))
            .andExpect(jsonPath("$.[*].streetAddress").value(hasItem(DEFAULT_STREET_ADDRESS)))
            .andExpect(jsonPath("$.[*].areaCode").value(hasItem(DEFAULT_AREA_CODE)))
            .andExpect(jsonPath("$.[*].town").value(hasItem(DEFAULT_TOWN)))
            .andExpect(jsonPath("$.[*].city").value(hasItem(DEFAULT_CITY)))
            .andExpect(jsonPath("$.[*].district").value(hasItem(DEFAULT_DISTRICT)))
            .andExpect(jsonPath("$.[*].state").value(hasItem(DEFAULT_STATE)))
            .andExpect(jsonPath("$.[*].region").value(hasItem(DEFAULT_REGION)))
            .andExpect(jsonPath("$.[*].country").value(hasItem(DEFAULT_COUNTRY)))
            .andExpect(jsonPath("$.[*].createdDate").value(hasItem(DEFAULT_CREATED_DATE.toString())))
            .andExpect(jsonPath("$.[*].modifiedDate").value(hasItem(DEFAULT_MODIFIED_DATE.toString())))
            .andExpect(jsonPath("$.[*].createdBy").value(hasItem(DEFAULT_CREATED_BY)))
            .andExpect(jsonPath("$.[*].modifiedBy").value(hasItem(DEFAULT_MODIFIED_BY)));
    }

    @Test
    void getAddress() throws Exception {
        // Initialize the database
        addressRepository.save(address);

        // Get the address
        restAddressMockMvc
            .perform(get(ENTITY_API_URL_ID, address.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(address.getId()))
            .andExpect(jsonPath("$.digitalAddress").value(DEFAULT_DIGITAL_ADDRESS))
            .andExpect(jsonPath("$.streetAddress").value(DEFAULT_STREET_ADDRESS))
            .andExpect(jsonPath("$.areaCode").value(DEFAULT_AREA_CODE))
            .andExpect(jsonPath("$.town").value(DEFAULT_TOWN))
            .andExpect(jsonPath("$.city").value(DEFAULT_CITY))
            .andExpect(jsonPath("$.district").value(DEFAULT_DISTRICT))
            .andExpect(jsonPath("$.state").value(DEFAULT_STATE))
            .andExpect(jsonPath("$.region").value(DEFAULT_REGION))
            .andExpect(jsonPath("$.country").value(DEFAULT_COUNTRY))
            .andExpect(jsonPath("$.createdDate").value(DEFAULT_CREATED_DATE.toString()))
            .andExpect(jsonPath("$.modifiedDate").value(DEFAULT_MODIFIED_DATE.toString()))
            .andExpect(jsonPath("$.createdBy").value(DEFAULT_CREATED_BY))
            .andExpect(jsonPath("$.modifiedBy").value(DEFAULT_MODIFIED_BY));
    }

    @Test
    void getNonExistingAddress() throws Exception {
        // Get the address
        restAddressMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingAddress() throws Exception {
        // Initialize the database
        addressRepository.save(address);

        int databaseSizeBeforeUpdate = addressRepository.findAll().size();

        // Update the address
        Address updatedAddress = addressRepository.findById(address.getId()).orElseThrow();
        updatedAddress
            .digitalAddress(UPDATED_DIGITAL_ADDRESS)
            .streetAddress(UPDATED_STREET_ADDRESS)
            .areaCode(UPDATED_AREA_CODE)
            .town(UPDATED_TOWN)
            .city(UPDATED_CITY)
            .district(UPDATED_DISTRICT)
            .state(UPDATED_STATE)
            .region(UPDATED_REGION)
            .country(UPDATED_COUNTRY)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);

        restAddressMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedAddress.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(updatedAddress))
            )
            .andExpect(status().isOk());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
        Address testAddress = addressList.get(addressList.size() - 1);
        assertThat(testAddress.getDigitalAddress()).isEqualTo(UPDATED_DIGITAL_ADDRESS);
        assertThat(testAddress.getStreetAddress()).isEqualTo(UPDATED_STREET_ADDRESS);
        assertThat(testAddress.getAreaCode()).isEqualTo(UPDATED_AREA_CODE);
        assertThat(testAddress.getTown()).isEqualTo(UPDATED_TOWN);
        assertThat(testAddress.getCity()).isEqualTo(UPDATED_CITY);
        assertThat(testAddress.getDistrict()).isEqualTo(UPDATED_DISTRICT);
        assertThat(testAddress.getState()).isEqualTo(UPDATED_STATE);
        assertThat(testAddress.getRegion()).isEqualTo(UPDATED_REGION);
        assertThat(testAddress.getCountry()).isEqualTo(UPDATED_COUNTRY);
        assertThat(testAddress.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testAddress.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testAddress.getCreatedBy()).isEqualTo(UPDATED_CREATED_BY);
        assertThat(testAddress.getModifiedBy()).isEqualTo(UPDATED_MODIFIED_BY);
    }

    @Test
    void putNonExistingAddress() throws Exception {
        int databaseSizeBeforeUpdate = addressRepository.findAll().size();
        address.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restAddressMockMvc
            .perform(
                put(ENTITY_API_URL_ID, address.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(address))
            )
            .andExpect(status().isBadRequest());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchAddress() throws Exception {
        int databaseSizeBeforeUpdate = addressRepository.findAll().size();
        address.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restAddressMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(address))
            )
            .andExpect(status().isBadRequest());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamAddress() throws Exception {
        int databaseSizeBeforeUpdate = addressRepository.findAll().size();
        address.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restAddressMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(address)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateAddressWithPatch() throws Exception {
        // Initialize the database
        addressRepository.save(address);

        int databaseSizeBeforeUpdate = addressRepository.findAll().size();

        // Update the address using partial update
        Address partialUpdatedAddress = new Address();
        partialUpdatedAddress.setId(address.getId());

        partialUpdatedAddress.streetAddress(UPDATED_STREET_ADDRESS).region(UPDATED_REGION).createdBy(UPDATED_CREATED_BY);

        restAddressMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedAddress.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedAddress))
            )
            .andExpect(status().isOk());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
        Address testAddress = addressList.get(addressList.size() - 1);
        assertThat(testAddress.getDigitalAddress()).isEqualTo(DEFAULT_DIGITAL_ADDRESS);
        assertThat(testAddress.getStreetAddress()).isEqualTo(UPDATED_STREET_ADDRESS);
        assertThat(testAddress.getAreaCode()).isEqualTo(DEFAULT_AREA_CODE);
        assertThat(testAddress.getTown()).isEqualTo(DEFAULT_TOWN);
        assertThat(testAddress.getCity()).isEqualTo(DEFAULT_CITY);
        assertThat(testAddress.getDistrict()).isEqualTo(DEFAULT_DISTRICT);
        assertThat(testAddress.getState()).isEqualTo(DEFAULT_STATE);
        assertThat(testAddress.getRegion()).isEqualTo(UPDATED_REGION);
        assertThat(testAddress.getCountry()).isEqualTo(DEFAULT_COUNTRY);
        assertThat(testAddress.getCreatedDate()).isEqualTo(DEFAULT_CREATED_DATE);
        assertThat(testAddress.getModifiedDate()).isEqualTo(DEFAULT_MODIFIED_DATE);
        assertThat(testAddress.getCreatedBy()).isEqualTo(UPDATED_CREATED_BY);
        assertThat(testAddress.getModifiedBy()).isEqualTo(DEFAULT_MODIFIED_BY);
    }

    @Test
    void fullUpdateAddressWithPatch() throws Exception {
        // Initialize the database
        addressRepository.save(address);

        int databaseSizeBeforeUpdate = addressRepository.findAll().size();

        // Update the address using partial update
        Address partialUpdatedAddress = new Address();
        partialUpdatedAddress.setId(address.getId());

        partialUpdatedAddress
            .digitalAddress(UPDATED_DIGITAL_ADDRESS)
            .streetAddress(UPDATED_STREET_ADDRESS)
            .areaCode(UPDATED_AREA_CODE)
            .town(UPDATED_TOWN)
            .city(UPDATED_CITY)
            .district(UPDATED_DISTRICT)
            .state(UPDATED_STATE)
            .region(UPDATED_REGION)
            .country(UPDATED_COUNTRY)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);

        restAddressMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedAddress.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedAddress))
            )
            .andExpect(status().isOk());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
        Address testAddress = addressList.get(addressList.size() - 1);
        assertThat(testAddress.getDigitalAddress()).isEqualTo(UPDATED_DIGITAL_ADDRESS);
        assertThat(testAddress.getStreetAddress()).isEqualTo(UPDATED_STREET_ADDRESS);
        assertThat(testAddress.getAreaCode()).isEqualTo(UPDATED_AREA_CODE);
        assertThat(testAddress.getTown()).isEqualTo(UPDATED_TOWN);
        assertThat(testAddress.getCity()).isEqualTo(UPDATED_CITY);
        assertThat(testAddress.getDistrict()).isEqualTo(UPDATED_DISTRICT);
        assertThat(testAddress.getState()).isEqualTo(UPDATED_STATE);
        assertThat(testAddress.getRegion()).isEqualTo(UPDATED_REGION);
        assertThat(testAddress.getCountry()).isEqualTo(UPDATED_COUNTRY);
        assertThat(testAddress.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testAddress.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testAddress.getCreatedBy()).isEqualTo(UPDATED_CREATED_BY);
        assertThat(testAddress.getModifiedBy()).isEqualTo(UPDATED_MODIFIED_BY);
    }

    @Test
    void patchNonExistingAddress() throws Exception {
        int databaseSizeBeforeUpdate = addressRepository.findAll().size();
        address.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restAddressMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, address.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(address))
            )
            .andExpect(status().isBadRequest());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchAddress() throws Exception {
        int databaseSizeBeforeUpdate = addressRepository.findAll().size();
        address.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restAddressMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(address))
            )
            .andExpect(status().isBadRequest());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamAddress() throws Exception {
        int databaseSizeBeforeUpdate = addressRepository.findAll().size();
        address.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restAddressMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(TestUtil.convertObjectToJsonBytes(address)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Address in the database
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteAddress() throws Exception {
        // Initialize the database
        addressRepository.save(address);

        int databaseSizeBeforeDelete = addressRepository.findAll().size();

        // Delete the address
        restAddressMockMvc
            .perform(delete(ENTITY_API_URL_ID, address.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        List<Address> addressList = addressRepository.findAll();
        assertThat(addressList).hasSize(databaseSizeBeforeDelete - 1);
    }

    @Test
    void searchAddress() throws Exception {
        // Initialize the database
        address = addressRepository.save(address);

        // Search the address
        restAddressMockMvc
            .perform(get(ENTITY_SEARCH_API_URL + "?query=id:" + address.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(address.getId())))
            .andExpect(jsonPath("$.[*].digitalAddress").value(hasItem(DEFAULT_DIGITAL_ADDRESS)))
            .andExpect(jsonPath("$.[*].streetAddress").value(hasItem(DEFAULT_STREET_ADDRESS)))
            .andExpect(jsonPath("$.[*].areaCode").value(hasItem(DEFAULT_AREA_CODE)))
            .andExpect(jsonPath("$.[*].town").value(hasItem(DEFAULT_TOWN)))
            .andExpect(jsonPath("$.[*].city").value(hasItem(DEFAULT_CITY)))
            .andExpect(jsonPath("$.[*].district").value(hasItem(DEFAULT_DISTRICT)))
            .andExpect(jsonPath("$.[*].state").value(hasItem(DEFAULT_STATE)))
            .andExpect(jsonPath("$.[*].region").value(hasItem(DEFAULT_REGION)))
            .andExpect(jsonPath("$.[*].country").value(hasItem(DEFAULT_COUNTRY)))
            .andExpect(jsonPath("$.[*].createdDate").value(hasItem(DEFAULT_CREATED_DATE.toString())))
            .andExpect(jsonPath("$.[*].modifiedDate").value(hasItem(DEFAULT_MODIFIED_DATE.toString())))
            .andExpect(jsonPath("$.[*].createdBy").value(hasItem(DEFAULT_CREATED_BY)))
            .andExpect(jsonPath("$.[*].modifiedBy").value(hasItem(DEFAULT_MODIFIED_BY)));
    }
}
