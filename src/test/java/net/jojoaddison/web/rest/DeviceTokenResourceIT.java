package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.DeviceToken;
import net.jojoaddison.repository.DeviceTokenRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link DeviceTokenResource} (MOB9).
 */
@IntegrationTest
@AutoConfigureMockMvc
class DeviceTokenResourceIT {

    private static final String NURSE = "device-nurse";
    private static final String CARER = "device-carer";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        deviceTokenRepository.deleteAll();
    }

    private String body(String token, String platform) {
        return "{\"token\":\"" + token + "\",\"platform\":\"" + platform + "\",\"appVersion\":\"0.1.0\",\"langKey\":\"en\"}";
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void registersADevice() throws Exception {
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-1", "ANDROID")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accountId").value(accountIdFor(NURSE)))
            .andExpect(jsonPath("$.platform").value("ANDROID"));

        assertThat(deviceTokenRepository.findByToken("tok-1")).isPresent();
    }

    /**
     * The security trap this endpoint exists to avoid.
     *
     * <p>{@code POST /api/**} requires {@code CLINICAL_MUTATION}, so without the
     * {@code /api/notifications/**} rule ordered above it, every read-only role would get a silent
     * 403 registering a device and simply never receive notifications.
     */
    @Test
    @WithMockGatewayUser(login = CARER, authorities = { "ROLE_CARER" })
    void aReadOnlyRoleCanStillRegisterADevice() throws Exception {
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-carer", "IOS")))
            .andExpect(status().isCreated());
    }

    /**
     * And so can a token bearing {@code ROLE_ANGEL}, which is not an authority of this subsystem at all
     * (docs/backlog.md item 44) but still arrives — hc-patient mints it, the three gateways share one
     * signing key and this service validates no issuer, and an account on a long-lived database may
     * hold a grant made before the removal. The point of the case is unchanged and is worth keeping in
     * this shape: {@code /api/notifications/**} is {@code .authenticated()} above the mutation matrix,
     * so registering a device does not depend on holding any recognised role, and an unrecognised
     * authority does not make a caller less authenticated than a role-less applicant.
     */
    @Test
    @WithMockGatewayUser(login = "device-angel", authorities = { "ROLE_ANGEL" })
    void soCanACallerHoldingAnAuthorityThisServiceDoesNotKnow() throws Exception {
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-angel", "IOS")))
            .andExpect(status().isCreated());
    }

    @Test
    void anonymousCannotRegister() throws Exception {
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-x", "IOS")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void rejectsAnEmptyToken() throws Exception {
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"IOS\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void reRegisteringTheSameTokenUpdatesRatherThanDuplicates() throws Exception {
        restMockMvc.perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-1", "ANDROID")));
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-1", "ANDROID")))
            .andExpect(status().isCreated());

        assertThat(deviceTokenRepository.findAll()).hasSize(1);
    }

    /**
     * The most important correctness detail in the feature.
     *
     * <p>FCM reuses a registration token when a second user signs in on the same handset. Rejecting
     * the conflict would leave the old mapping in place and deliver the first clinician's
     * notifications to the second.
     */
    @Test
    void aTokenReusedByAnotherUserIsREASSIGNED() throws Exception {
        restMockMvc
            .perform(
                post("/api/notifications/devices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("shared-handset", "ANDROID"))
                    .with(gatewayUser(NURSE))
            )
            .andExpect(status().isCreated());

        restMockMvc
            .perform(
                post("/api/notifications/devices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("shared-handset", "ANDROID"))
                    .with(gatewayUser(CARER))
            )
            .andExpect(status().isCreated());

        assertThat(deviceTokenRepository.findAll()).hasSize(1);
        assertThat(deviceTokenRepository.findByToken("shared-handset").orElseThrow().getAccountId()).isEqualTo(accountIdFor(CARER));
        // The first clinician must no longer be a target for this handset.
        assertThat(deviceTokenRepository.findAllByAccountIdAndDisabledAtIsNull(accountIdFor(NURSE))).isEmpty();
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void reRegisteringRevivesATokenPreviouslyPrunedAsDead() throws Exception {
        DeviceToken dead = new DeviceToken();
        dead.setToken("tok-revive");
        dead.setAccountId(accountIdFor(NURSE));
        dead.setDisabledAt(java.time.Instant.now());
        dead.setDisabledReason("UNREGISTERED");
        deviceTokenRepository.save(dead);

        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-revive", "ANDROID")))
            .andExpect(status().isCreated());

        // The app is plainly installed again, so it must become a live target.
        assertThat(deviceTokenRepository.findByToken("tok-revive").orElseThrow().isActive()).isTrue();
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void deregistersOnSignOut() throws Exception {
        restMockMvc.perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-1", "IOS")));

        restMockMvc.perform(delete("/api/notifications/devices/{token}", "tok-1")).andExpect(status().isNoContent());

        assertThat(deviceTokenRepository.findByToken("tok-1")).isEmpty();
    }

    @Test
    void cannotDeregisterSomebodyElsesDevice() throws Exception {
        DeviceToken theirs = new DeviceToken();
        theirs.setToken("not-yours");
        theirs.setAccountId(accountIdFor(CARER));
        deviceTokenRepository.save(theirs);

        restMockMvc
            .perform(delete("/api/notifications/devices/{token}", "not-yours").with(gatewayUser(NURSE)))
            .andExpect(status().isNoContent());

        // Silent 204 either way so the endpoint cannot be used to probe for tokens — but the
        // device must survive.
        assertThat(deviceTokenRepository.findByToken("not-yours")).isPresent();
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void listsOnlyYourOwnDevices() throws Exception {
        DeviceToken theirs = new DeviceToken();
        theirs.setToken("theirs");
        theirs.setAccountId(accountIdFor(CARER));
        deviceTokenRepository.save(theirs);

        restMockMvc.perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("mine", "IOS")));

        restMockMvc
            .perform(get("/api/notifications/devices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].token").value("mine"));
    }
}
