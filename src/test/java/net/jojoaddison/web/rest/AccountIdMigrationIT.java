package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.DeviceToken;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.repository.DeviceTokenRepository;
import net.jojoaddison.repository.OrphanedAccountRowRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.GatewayUserClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The migration against a real database (backlog.md item 50).
 *
 * <p>{@code AccountIdMigrationServiceUnitTest} pins which of three things happens to a row; this pins
 * that the endpoint is reachable, admin-only, and that the rows it moves are afterwards found by the
 * lookup the rest of the service uses — which a mocked {@code MongoTemplate} cannot show, because the
 * unique sparse index on {@code profile.account_id} and Spring Data's field mapping are exactly the
 * parts a mock stands in for.
 *
 * <p>{@link GatewayUserClient} is mocked. The mapping comes from another service over HTTP and what is
 * under test is what this one does with it; a test that reached a gateway would report on what was
 * running on the machine.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AccountIdMigrationIT {

    private static final String ENDPOINT = "/api/admin/account-id-migration";

    private static final String KNOWN_LOGIN = "migrating.nurse";
    private static final String KNOWN_ACCOUNT_ID = "68bd4e2a91c30d5f7a1e4c02";

    /** A login the gateway has never heard of: an account since deleted, or a sibling stack's. */
    private static final String ORPHAN_LOGIN = "since.deleted";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private OrphanedAccountRowRepository orphanedAccountRowRepository;

    @MockitoBean
    private GatewayUserClient gatewayUserClient;

    @BeforeEach
    @AfterEach
    void clean() {
        profileRepository.deleteAll();
        applicationRepository.deleteAll();
        deviceTokenRepository.deleteAll();
        orphanedAccountRowRepository.deleteAll();
    }

    @BeforeEach
    void theGatewayKnowsOneAccount() {
        when(gatewayUserClient.loginToAccountId()).thenReturn(Map.of(KNOWN_LOGIN, KNOWN_ACCOUNT_ID));
    }

    /**
     * The whole point, end to end: a profile stored under a login is afterwards found by the account
     * id — and is <b>no longer</b> found by the login, which is the half that says the second key is
     * gone rather than merely joined by a first.
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void aProfileStoredUnderALoginIsAfterwardsFoundByTheAccountId() throws Exception {
        Profile stored = profileRepository.save(new Profile().accountId(KNOWN_LOGIN).firstName("Ama").lastName("Serwaa"));

        restMockMvc.perform(post(ENDPOINT).param("dryRun", "false")).andExpect(status().isOk());

        assertThat(profileRepository.findByAccountId(KNOWN_ACCOUNT_ID)).map(Profile::getId).contains(stored.getId());
        assertThat(profileRepository.findByAccountId(KNOWN_LOGIN)).isEmpty();
    }

    /**
     * Every collection at once, because the account key is a lookup key: a clinician whose profile has
     * moved and whose application has not is findable by neither. Item 53's review calls a row-by-row
     * fill destructive rather than slow, for this reason.
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void theApplicationAndTheDeviceTokenMoveWithTheProfile() throws Exception {
        profileRepository.save(new Profile().accountId(KNOWN_LOGIN).firstName("Ama"));
        applicationRepository.save(new ProfessionalApplication().accountId(KNOWN_LOGIN).login(KNOWN_LOGIN).status(OnboardingStatus.ACTIVE));
        DeviceToken device = new DeviceToken();
        device.setToken("fcm-token-1");
        device.setAccountId(KNOWN_LOGIN);
        deviceTokenRepository.save(device);

        restMockMvc.perform(post(ENDPOINT).param("dryRun", "false")).andExpect(status().isOk());

        assertThat(applicationRepository.findByAccountId(KNOWN_ACCOUNT_ID)).isPresent();
        assertThat(deviceTokenRepository.findAllByAccountId(KNOWN_ACCOUNT_ID)).hasSize(1);
        assertThat(applicationRepository.findByAccountId(KNOWN_ACCOUNT_ID).orElseThrow().getLogin())
            .as("the login stays where it is — it is the display name, not the key")
            .isEqualTo(KNOWN_LOGIN);
    }

    /**
     * <b>The row that resolves to nothing.</b> Its key is cleared and the value recorded; it is not
     * left holding a login and no id is invented for it (item 50 § "Do not").
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void anUnresolvableRowIsQuarantinedRatherThanLeftHoldingItsLogin() throws Exception {
        Profile orphan = profileRepository.save(new Profile().accountId(ORPHAN_LOGIN).firstName("Who"));

        restMockMvc.perform(post(ENDPOINT).param("dryRun", "false")).andExpect(status().isOk());

        assertThat(profileRepository.findById(orphan.getId()).orElseThrow().getAccountId()).isNull();
        assertThat(profileRepository.findByAccountId(ORPHAN_LOGIN)).isEmpty();
        assertThat(orphanedAccountRowRepository.findAll())
            .singleElement()
            .satisfies(row -> {
                assertThat(row.getOrphanedValue()).isEqualTo(ORPHAN_LOGIN);
                assertThat(row.getCollectionName()).isEqualTo("profile");
                assertThat(row.getFieldName()).isEqualTo("accountId");
            });
    }

    /** Running it twice moves nothing the second time and records no second quarantine row. */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void aSecondRunIsANoOp() throws Exception {
        profileRepository.save(new Profile().accountId(KNOWN_LOGIN).firstName("Ama"));
        profileRepository.save(new Profile().accountId(ORPHAN_LOGIN).firstName("Who"));

        restMockMvc.perform(post(ENDPOINT).param("dryRun", "false")).andExpect(status().isOk());
        restMockMvc
            .perform(post(ENDPOINT).param("dryRun", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.collections[0].rewritten").value(0))
            .andExpect(jsonPath("$.collections[0].quarantined").value(0));

        assertThat(orphanedAccountRowRepository.findAll()).hasSize(1);
        assertThat(profileRepository.findByAccountId(KNOWN_ACCOUNT_ID)).isPresent();
    }

    /** The default is safe: a bare POST reports and changes nothing. */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void theDefaultIsADryRun() throws Exception {
        profileRepository.save(new Profile().accountId(KNOWN_LOGIN).firstName("Ama"));

        restMockMvc.perform(post(ENDPOINT)).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));

        assertThat(profileRepository.findByAccountId(KNOWN_LOGIN)).isPresent();
        assertThat(profileRepository.findByAccountId(KNOWN_ACCOUNT_ID)).isEmpty();
    }

    /**
     * Admin only. A doctor holds {@code CLINICAL_MUTATION} and may POST anywhere else under
     * {@code /api}, which is exactly why this needs its own case: the endpoint's protection comes from
     * the {@code /api/admin/**} prefix, and moving the handler would silently take it away.
     */
    @Test
    @WithMockGatewayUser(login = "doctor", authorities = { "ROLE_DOCTOR" })
    void aClinicianCannotRunIt() throws Exception {
        restMockMvc.perform(post(ENDPOINT).param("dryRun", "false")).andExpect(status().isForbidden());
    }

    /**
     * <b>The cutover, stated as a request.</b> A token minted before 2026-09-07 carries no {@code uid},
     * so its holder resolves to nobody and the own-scoped surface refuses them — rather than falling
     * back to the subject, which would key this database on a value it no longer stores.
     *
     * <p>Lives here rather than beside the onboarding tests because it is the same decision as the
     * migration: one moves the stored values, the other stops the old ones arriving.
     */
    @Test
    @WithMockGatewayUser(login = KNOWN_LOGIN, authorities = { "ROLE_USER" }, withoutAccountId = true)
    void aTokenWithNoAccountIdClaimIsRefusedRatherThanResolvedToItsLogin() throws Exception {
        profileRepository.save(new Profile().accountId(KNOWN_LOGIN).firstName("Ama"));

        restMockMvc.perform(get("/api/onboarding/profile")).andExpect(status().isUnauthorized());
    }
}
