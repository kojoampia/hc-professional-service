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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Address;
import net.jojoaddison.domain.AddressTestSamples;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link ProfileResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser(authorities = { "ROLE_DOCTOR" })
class ProfileResourceIT {

    private static final String DEFAULT_FIRST_NAME = "AAAAAAAAAA";
    private static final String UPDATED_FIRST_NAME = "BBBBBBBBBB";

    private static final String DEFAULT_MIDDLE_NAMES = "AAAAAAAAAA";
    private static final String UPDATED_MIDDLE_NAMES = "BBBBBBBBBB";

    private static final String DEFAULT_LAST_NAME = "AAAAAAAAAA";
    private static final String UPDATED_LAST_NAME = "BBBBBBBBBB";

    private static final LocalDate DEFAULT_BIRTH_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_BIRTH_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final String DEFAULT_SEX = "F";
    private static final String UPDATED_SEX = "M";

    private static final String DEFAULT_MOBILE_PHONE = "AAAAAAAAAA";
    private static final String UPDATED_MOBILE_PHONE = "BBBBBBBBBB";

    private static final String DEFAULT_PHONE_NUMBER = "AAAAAAAAAA";
    private static final String UPDATED_PHONE_NUMBER = "BBBBBBBBBB";

    private static final String DEFAULT_EMAIL = "AAAAAAAAAA";
    private static final String UPDATED_EMAIL = "BBBBBBBBBB";

    private static final String DEFAULT_CARD_TYPE = "AAAAAAAAAA";
    private static final String UPDATED_CARD_TYPE = "BBBBBBBBBB";

    private static final String DEFAULT_CARD_NUMBER = "AAAAAAAAAA";
    private static final String UPDATED_CARD_NUMBER = "BBBBBBBBBB";

    private static final Address DEFAULT_ADDRESS = AddressTestSamples.getAddressSample1();
    private static final Address UPDATED_ADDRESS = AddressTestSamples.getAddressSample2();

    private static final String ENTITY_API_URL = "/api/profiles";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private MockMvc restProfileMockMvc;

    private Profile profile;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Profile createEntity() {
        Profile profile = new Profile()
            .firstName(DEFAULT_FIRST_NAME)
            .middleNames(DEFAULT_MIDDLE_NAMES)
            .lastName(DEFAULT_LAST_NAME)
            .birthDate(DEFAULT_BIRTH_DATE)
            .sex(DEFAULT_SEX)
            .mobilePhone(DEFAULT_MOBILE_PHONE)
            .phoneNumber(DEFAULT_PHONE_NUMBER)
            .email(DEFAULT_EMAIL)
            .cardType(DEFAULT_CARD_TYPE)
            .cardNumber(DEFAULT_CARD_NUMBER)
            .address(DEFAULT_ADDRESS);
        return profile;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Profile createUpdatedEntity() {
        Profile profile = new Profile()
            .firstName(UPDATED_FIRST_NAME)
            .middleNames(UPDATED_MIDDLE_NAMES)
            .lastName(UPDATED_LAST_NAME)
            .birthDate(UPDATED_BIRTH_DATE)
            .sex(UPDATED_SEX)
            .mobilePhone(UPDATED_MOBILE_PHONE)
            .phoneNumber(UPDATED_PHONE_NUMBER)
            .email(UPDATED_EMAIL)
            .cardType(UPDATED_CARD_TYPE)
            .cardNumber(UPDATED_CARD_NUMBER)
            .address(UPDATED_ADDRESS);
        return profile;
    }

    @BeforeEach
    public void initTest() {
        profileRepository.deleteAll();
        profile = createEntity();
    }

    /**
     * <b>The create is refused, and the generated test that asserted a 201 is what this replaces</b>
     * (backlog.md item 66).
     *
     * <p>It asserted the eleven fields came back and never looked at {@code accountId}, so it stayed
     * green through item 54 — which made that field {@code READ_ONLY} over HTTP for update
     * <em>and</em> create, and turned every {@code POST} here into a profile belonging to nobody that
     * no later call could give an owner. Nobody noticed because the defect was framed as reassignment
     * and every test inherited the frame — and because the quality stack's thirteen seeded profiles
     * were written before the hardening, so they are linked and look right.
     *
     * <p>Both bodies the old pair sent are asserted here — with an id and without — because they now
     * get the same answer for the same reason, and a 400 that arrived only for the id would prove
     * nothing about the create.
     */
    @Test
    void aCreateIsRefusedRatherThanMakingAProfileThatBelongsToNobody() throws Exception {
        int databaseSizeBeforeCreate = profileRepository.findAll().size();

        String body = restProfileMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(profile)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body)
            .as("a refusal that will not say where the caller should go instead is item 46 wearing a 400")
            .contains("PUT /api/onboarding/profile");

        // The same, with an id — the case the generated createProfileWithExistingId covered.
        profile.setId("existing_id");
        restProfileMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(profile)))
            .andExpect(status().isBadRequest());

        assertThat(profileRepository.findAll()).as("a refused create writes nothing at all").hasSize(databaseSizeBeforeCreate);
    }

    /**
     * <b>The create cannot be aimed at an account, and this is the test that decides whether item
     * 66's answer is safe.</b>
     *
     * <p>The rejected shape for item 66 was "make {@code accountId} writable on create, immutable
     * thereafter". Both halves of why it was rejected are exercised here, as raw JSON because a
     * hostile client is not obliged to use our serialiser:
     *
     * <ul>
     *   <li><b>Re-pointing an existing row.</b> The unique sparse index on {@code account_id} would
     *       refuse the write, but with a duplicate-key 500 rather than a decision — a database
     *       constraint answering an authorization question, which is the reasoning item 54 already
     *       recorded when it noted the index forced the takeover into two writes instead of
     *       preventing it.</li>
     *   <li><b>Claiming a login that has no row yet</b> — the half the index cannot see, and the
     *       reason a creation-time exception is not a smaller version of item 54's fix. Any of the
     *       six {@code CLINICAL_MUTATION} roles could author a colleague's profile before that
     *       colleague onboards, and {@code upsertOwnProfile} adopts the row it finds by
     *       {@code accountId}: the victim would inherit an identity somebody else wrote, including
     *       the push preferences item 60 refused to let this same role set.</li>
     * </ul>
     */
    @Test
    void aCreateCannotBeAimedAtAnAccount() throws Exception {
        Profile victim = profileRepository.save(new Profile().firstName("Ama").lastName("Serwaa").accountId("ama.serwaa"));
        int before = profileRepository.findAll().size();

        // 1. an account that already has a profile
        restProfileMockMvc
            .perform(
                post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Mal\",\"accountId\":\"ama.serwaa\"}")
            )
            .andExpect(status().isBadRequest());

        // 2. an account that does not — invited, not yet onboarded, and the index cannot see it
        restProfileMockMvc
            .perform(
                post(ENTITY_API_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"firstName\":\"Mal\",\"accountId\":\"not.yet.onboarded\"}")
            )
            .andExpect(status().isBadRequest());

        assertThat(profileRepository.findAll()).as("neither attempt may leave a row behind").hasSize(before);
        assertThat(profileRepository.findByAccountId("ama.serwaa").orElseThrow().getId())
            .as("the victim still owns their profile")
            .isEqualTo(victim.getId());
        assertThat(profileRepository.findByAccountId("not.yet.onboarded"))
            .as("and the account with no profile still has none to inherit")
            .isEmpty();
    }

    /**
     * <b>The positive half: a profile does end up linked, through the caller item 66 settled on.</b>
     *
     * <p>Item 66 asked how a profile becomes attached to an account now that a create cannot do it,
     * and the answer is that it never was a create's job — {@code PUT /api/onboarding/profile} force
     * -sets {@code accountId} to the caller's own token and has done since WP4. This runs as an
     * applicant holding nothing but {@code ROLE_USER}, which is what an applicant really holds and
     * what {@code POST /api/profiles} refuses outright, so it also shows the two paths are not
     * competing for the same caller.
     *
     * <p>It passes against {@code main} on purpose. The refusal above is the change; this is the
     * control that says the refusal removed nothing anybody could use, and it fails loudly if a later
     * change breaks the path the refusal names.
     */
    @Test
    @WithMockUser(username = "item66.applicant", authorities = { "ROLE_USER" })
    void theApplicantPathIsWhatLinksAProfileToAnAccount() throws Exception {
        restProfileMockMvc
            .perform(
                put("/api/onboarding/profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"firstName\":\"Kofi\",\"lastName\":\"Mensah\"}")
            )
            .andExpect(status().isOk());

        Profile linked = profileRepository.findByAccountId("item66.applicant").orElseThrow();
        assertThat(linked.getFirstName()).isEqualTo("Kofi");
        assertThat(linked.getAccountId()).isEqualTo("item66.applicant");
    }

    @Test
    void getAllProfiles() throws Exception {
        // Initialize the database
        profileRepository.save(profile);

        // Get all the profileList
        restProfileMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(profile.getId())))
            .andExpect(jsonPath("$.[*].firstName").value(hasItem(DEFAULT_FIRST_NAME)))
            .andExpect(jsonPath("$.[*].middleNames").value(hasItem(DEFAULT_MIDDLE_NAMES)))
            .andExpect(jsonPath("$.[*].lastName").value(hasItem(DEFAULT_LAST_NAME)))
            .andExpect(jsonPath("$.[*].birthDate").value(hasItem(DEFAULT_BIRTH_DATE.toString())))
            .andExpect(jsonPath("$.[*].sex").value(hasItem(DEFAULT_SEX)))
            .andExpect(jsonPath("$.[*].mobilePhone").value(hasItem(DEFAULT_MOBILE_PHONE)))
            .andExpect(jsonPath("$.[*].phoneNumber").value(hasItem(DEFAULT_PHONE_NUMBER)))
            .andExpect(jsonPath("$.[*].email").value(hasItem(DEFAULT_EMAIL)))
            .andExpect(jsonPath("$.[*].cardType").value(hasItem(DEFAULT_CARD_TYPE)))
            .andExpect(jsonPath("$.[*].cardNumber").value(hasItem(DEFAULT_CARD_NUMBER)));
    }

    @Test
    void getProfile() throws Exception {
        // Initialize the database
        profileRepository.save(profile);

        // Get the profile
        restProfileMockMvc
            .perform(get(ENTITY_API_URL_ID, profile.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(profile.getId()))
            .andExpect(jsonPath("$.firstName").value(DEFAULT_FIRST_NAME))
            .andExpect(jsonPath("$.middleNames").value(DEFAULT_MIDDLE_NAMES))
            .andExpect(jsonPath("$.lastName").value(DEFAULT_LAST_NAME))
            .andExpect(jsonPath("$.birthDate").value(DEFAULT_BIRTH_DATE.toString()))
            .andExpect(jsonPath("$.sex").value(DEFAULT_SEX))
            .andExpect(jsonPath("$.mobilePhone").value(DEFAULT_MOBILE_PHONE))
            .andExpect(jsonPath("$.phoneNumber").value(DEFAULT_PHONE_NUMBER))
            .andExpect(jsonPath("$.email").value(DEFAULT_EMAIL))
            .andExpect(jsonPath("$.cardType").value(DEFAULT_CARD_TYPE))
            .andExpect(jsonPath("$.cardNumber").value(DEFAULT_CARD_NUMBER))
            .andExpect(jsonPath("$.address").value(DEFAULT_ADDRESS));
    }

    @Test
    void getNonExistingProfile() throws Exception {
        // Get the profile
        restProfileMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingProfile() throws Exception {
        // Initialize the database
        profileRepository.save(profile);

        int databaseSizeBeforeUpdate = profileRepository.findAll().size();

        // Update the profile
        Profile updatedProfile = profileRepository.findById(profile.getId()).orElseThrow();
        updatedProfile
            .firstName(UPDATED_FIRST_NAME)
            .middleNames(UPDATED_MIDDLE_NAMES)
            .lastName(UPDATED_LAST_NAME)
            .birthDate(UPDATED_BIRTH_DATE)
            .sex(UPDATED_SEX)
            .mobilePhone(UPDATED_MOBILE_PHONE)
            .phoneNumber(UPDATED_PHONE_NUMBER)
            .email(UPDATED_EMAIL)
            .cardType(UPDATED_CARD_TYPE)
            .cardNumber(UPDATED_CARD_NUMBER)
            .address(UPDATED_ADDRESS);

        restProfileMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedProfile.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(updatedProfile))
            )
            .andExpect(status().isOk());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
        Profile testProfile = profileList.get(profileList.size() - 1);
        assertThat(testProfile.getFirstName()).isEqualTo(UPDATED_FIRST_NAME);
        assertThat(testProfile.getMiddleNames()).isEqualTo(UPDATED_MIDDLE_NAMES);
        assertThat(testProfile.getLastName()).isEqualTo(UPDATED_LAST_NAME);
        assertThat(testProfile.getBirthDate()).isEqualTo(UPDATED_BIRTH_DATE);
        assertThat(testProfile.getSex()).isEqualTo(UPDATED_SEX);
        assertThat(testProfile.getMobilePhone()).isEqualTo(UPDATED_MOBILE_PHONE);
        assertThat(testProfile.getPhoneNumber()).isEqualTo(UPDATED_PHONE_NUMBER);
        assertThat(testProfile.getEmail()).isEqualTo(UPDATED_EMAIL);
        assertThat(testProfile.getCardType()).isEqualTo(UPDATED_CARD_TYPE);
        assertThat(testProfile.getCardNumber()).isEqualTo(UPDATED_CARD_NUMBER);
        assertThat(testProfile.getAddress()).isEqualTo(UPDATED_ADDRESS);
    }

    @Test
    void putNonExistingProfile() throws Exception {
        int databaseSizeBeforeUpdate = profileRepository.findAll().size();
        profile.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restProfileMockMvc
            .perform(put(ENTITY_API_URL_ID, profile.getId()).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(profile)))
            .andExpect(status().isBadRequest());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchProfile() throws Exception {
        int databaseSizeBeforeUpdate = profileRepository.findAll().size();
        profile.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restProfileMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsBytes(profile))
            )
            .andExpect(status().isBadRequest());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamProfile() throws Exception {
        int databaseSizeBeforeUpdate = profileRepository.findAll().size();
        profile.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restProfileMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsBytes(profile)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateProfileWithPatch() throws Exception {
        // Initialize the database
        profileRepository.save(profile);

        int databaseSizeBeforeUpdate = profileRepository.findAll().size();

        // Update the profile using partial update
        Profile partialUpdatedProfile = new Profile();
        partialUpdatedProfile.setId(profile.getId());

        partialUpdatedProfile
            .sex(UPDATED_SEX)
            .phoneNumber(UPDATED_PHONE_NUMBER)
            .email(UPDATED_EMAIL)
            .cardNumber(UPDATED_CARD_NUMBER)
            .address(UPDATED_ADDRESS);

        restProfileMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedProfile.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedProfile))
            )
            .andExpect(status().isOk());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
        Profile testProfile = profileList.get(profileList.size() - 1);
        assertThat(testProfile.getFirstName()).isEqualTo(DEFAULT_FIRST_NAME);
        assertThat(testProfile.getMiddleNames()).isEqualTo(DEFAULT_MIDDLE_NAMES);
        assertThat(testProfile.getLastName()).isEqualTo(DEFAULT_LAST_NAME);
        assertThat(testProfile.getBirthDate()).isEqualTo(DEFAULT_BIRTH_DATE);
        assertThat(testProfile.getSex()).isEqualTo(UPDATED_SEX);
        assertThat(testProfile.getMobilePhone()).isEqualTo(DEFAULT_MOBILE_PHONE);
        assertThat(testProfile.getPhoneNumber()).isEqualTo(UPDATED_PHONE_NUMBER);
        assertThat(testProfile.getEmail()).isEqualTo(UPDATED_EMAIL);
        assertThat(testProfile.getCardType()).isEqualTo(DEFAULT_CARD_TYPE);
        assertThat(testProfile.getCardNumber()).isEqualTo(UPDATED_CARD_NUMBER);
        assertThat(testProfile.getAddress()).isEqualTo(UPDATED_ADDRESS);
    }

    @Test
    void fullUpdateProfileWithPatch() throws Exception {
        // Initialize the database
        profileRepository.save(profile);

        int databaseSizeBeforeUpdate = profileRepository.findAll().size();

        // Update the profile using partial update
        Profile partialUpdatedProfile = new Profile();
        partialUpdatedProfile.setId(profile.getId());

        partialUpdatedProfile
            .firstName(UPDATED_FIRST_NAME)
            .middleNames(UPDATED_MIDDLE_NAMES)
            .lastName(UPDATED_LAST_NAME)
            .birthDate(UPDATED_BIRTH_DATE)
            .sex(UPDATED_SEX)
            .mobilePhone(UPDATED_MOBILE_PHONE)
            .phoneNumber(UPDATED_PHONE_NUMBER)
            .email(UPDATED_EMAIL)
            .cardType(UPDATED_CARD_TYPE)
            .cardNumber(UPDATED_CARD_NUMBER)
            .address(UPDATED_ADDRESS);

        restProfileMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedProfile.getId())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(partialUpdatedProfile))
            )
            .andExpect(status().isOk());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
        Profile testProfile = profileList.get(profileList.size() - 1);
        assertThat(testProfile.getFirstName()).isEqualTo(UPDATED_FIRST_NAME);
        assertThat(testProfile.getMiddleNames()).isEqualTo(UPDATED_MIDDLE_NAMES);
        assertThat(testProfile.getLastName()).isEqualTo(UPDATED_LAST_NAME);
        assertThat(testProfile.getBirthDate()).isEqualTo(UPDATED_BIRTH_DATE);
        assertThat(testProfile.getSex()).isEqualTo(UPDATED_SEX);
        assertThat(testProfile.getMobilePhone()).isEqualTo(UPDATED_MOBILE_PHONE);
        assertThat(testProfile.getPhoneNumber()).isEqualTo(UPDATED_PHONE_NUMBER);
        assertThat(testProfile.getEmail()).isEqualTo(UPDATED_EMAIL);
        assertThat(testProfile.getCardType()).isEqualTo(UPDATED_CARD_TYPE);
        assertThat(testProfile.getCardNumber()).isEqualTo(UPDATED_CARD_NUMBER);
        assertThat(testProfile.getAddress()).isEqualTo(UPDATED_ADDRESS);
    }

    @Test
    void patchNonExistingProfile() throws Exception {
        int databaseSizeBeforeUpdate = profileRepository.findAll().size();
        profile.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restProfileMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, profile.getId()).contentType("application/merge-patch+json").content(om.writeValueAsBytes(profile))
            )
            .andExpect(status().isBadRequest());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchProfile() throws Exception {
        int databaseSizeBeforeUpdate = profileRepository.findAll().size();
        profile.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restProfileMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsBytes(profile))
            )
            .andExpect(status().isBadRequest());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamProfile() throws Exception {
        int databaseSizeBeforeUpdate = profileRepository.findAll().size();
        profile.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restProfileMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(om.writeValueAsBytes(profile)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Profile in the database
        List<Profile> profileList = profileRepository.findAll();
        assertThat(profileList).hasSize(databaseSizeBeforeUpdate);
    }

    /**
     * There is no DELETE on this resource, and this asserts the absence rather than trusting it —
     * backlog item 56. The generated endpoint orphaned six collections — DutyRoster, Absence, Report,
     * PersonalDocument and ProfessionalApplication by professionalId or profileId, and Team.members,
     * which holds profile ids under a name carrying neither word — and announced nothing to hc-admin,
     * because a delete is the one change ProfileStatus's seven contracted fields cannot express.
     *
     * <p>Asserted as 405 rather than 404: the path pattern still matches GET/PUT/PATCH, so Spring
     * rejects the method rather than the route. A regeneration that quietly restores the mapping turns
     * this red.
     */
    @Test
    void thereIsNoDeleteOnThisResource() throws Exception {
        profileRepository.save(profile);
        int before = profileRepository.findAll().size();

        restProfileMockMvc
            .perform(delete(ENTITY_API_URL_ID, profile.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isMethodNotAllowed());

        assertThat(profileRepository.findAll()).hasSize(before);
    }

    /**
     * The account-takeover this resource allowed until 2026-09-08, written as the attack rather than as
     * a field assertion so that it stays honest.
     *
     * <p>{@code Profile.accountId} is the ownership check: {@code findByAccountId(login)} decides whose
     * identity and licence documents, roster, absences and patient directory a caller may read. This
     * class already runs as {@code ROLE_DOCTOR} — one of the six {@code CLINICAL_MUTATION} roles that
     * {@code PUT /api/**} admits — so the attacker here needs no privilege the test did not already
     * have. Sent as raw JSON because a hostile client is not obliged to use our serialiser, and
     * {@code READ_ONLY} only stops Jackson on the way in.
     *
     * <p>Two writes, because the unique sparse index on {@code account_id} blocks a straight collision:
     * park the attacker's own key, then claim the victim's. If either assertion below fails, a nurse can
     * download another clinician's passport.
     */
    @Test
    void aClinicianCannotClaimAnotherProfileByRewritingItsAccountId() throws Exception {
        Profile victim = new Profile().firstName("Ama").lastName("Serwaa").accountId("ama.serwaa");
        profileRepository.save(victim);
        Profile attacker = new Profile().firstName("Mal").lastName("Ory").accountId("mallory");
        profileRepository.save(attacker);

        // 1. park the attacker's own key, so the unique index cannot be what refuses step 2
        restProfileMockMvc
            .perform(
                put(ENTITY_API_URL_ID, attacker.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":\"" + attacker.getId() + "\",\"firstName\":\"Mal\",\"accountId\":\"parked\"}")
            )
            .andExpect(status().isOk());
        assertThat(profileRepository.findById(attacker.getId()).orElseThrow().getAccountId()).isEqualTo("mallory");

        // 2. claim the victim's
        restProfileMockMvc
            .perform(
                put(ENTITY_API_URL_ID, victim.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":\"" + victim.getId() + "\",\"firstName\":\"Ama\",\"accountId\":\"mallory\"}")
            )
            .andExpect(status().isOk());

        // The victim still owns their profile, and the attacker's login still resolves to their own.
        assertThat(profileRepository.findById(victim.getId()).orElseThrow().getAccountId()).isEqualTo("ama.serwaa");
        assertThat(profileRepository.findByAccountId("ama.serwaa").orElseThrow().getId()).isEqualTo(victim.getId());
        assertThat(profileRepository.findByAccountId("mallory").orElseThrow().getId()).isEqualTo(attacker.getId());
    }

    /**
     * The other half of the same field: a PUT that simply omits {@code accountId} must not clear it and
     * detach the clinician from their own documents. READ_ONLY guarantees every PUT omits it.
     */
    @Test
    void aPutThatOmitsAccountIdDoesNotClearIt() throws Exception {
        Profile stored = new Profile().firstName("Ama").lastName("Serwaa").accountId("ama.serwaa");
        profileRepository.save(stored);

        restProfileMockMvc
            .perform(
                put(ENTITY_API_URL_ID, stored.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":\"" + stored.getId() + "\",\"firstName\":\"Ama Updated\"}")
            )
            .andExpect(status().isOk());

        Profile after = profileRepository.findById(stored.getId()).orElseThrow();
        assertThat(after.getFirstName()).isEqualTo("Ama Updated");
        assertThat(after.getAccountId()).isEqualTo("ama.serwaa");
    }
}
