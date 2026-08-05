package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.DeviceToken;
import net.jojoaddison.repository.DeviceTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
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
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void registersADevice() throws Exception {
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-1", "ANDROID")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accountId").value(NURSE))
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
    @WithMockUser(username = CARER, authorities = { "ROLE_CARER" })
    void aReadOnlyRoleCanStillRegisterADevice() throws Exception {
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-carer", "IOS")))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "device-angel", authorities = { "ROLE_ANGEL" })
    void soCanAnAngel() throws Exception {
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
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void rejectsAnEmptyToken() throws Exception {
        restMockMvc
            .perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"IOS\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
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
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(NURSE))
            )
            .andExpect(status().isCreated());

        restMockMvc
            .perform(
                post("/api/notifications/devices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("shared-handset", "ANDROID"))
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(CARER))
            )
            .andExpect(status().isCreated());

        assertThat(deviceTokenRepository.findAll()).hasSize(1);
        assertThat(deviceTokenRepository.findByToken("shared-handset").orElseThrow().getAccountId()).isEqualTo(CARER);
        // The first clinician must no longer be a target for this handset.
        assertThat(deviceTokenRepository.findAllByAccountIdAndDisabledAtIsNull(NURSE)).isEmpty();
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void reRegisteringRevivesATokenPreviouslyPrunedAsDead() throws Exception {
        DeviceToken dead = new DeviceToken();
        dead.setToken("tok-revive");
        dead.setAccountId(NURSE);
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
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void deregistersOnSignOut() throws Exception {
        restMockMvc.perform(post("/api/notifications/devices").contentType(MediaType.APPLICATION_JSON).content(body("tok-1", "IOS")));

        restMockMvc.perform(delete("/api/notifications/devices/{token}", "tok-1")).andExpect(status().isNoContent());

        assertThat(deviceTokenRepository.findByToken("tok-1")).isEmpty();
    }

    @Test
    void cannotDeregisterSomebodyElsesDevice() throws Exception {
        DeviceToken theirs = new DeviceToken();
        theirs.setToken("not-yours");
        theirs.setAccountId(CARER);
        deviceTokenRepository.save(theirs);

        restMockMvc
            .perform(
                delete("/api/notifications/devices/{token}", "not-yours").with(
                    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(NURSE)
                )
            )
            .andExpect(status().isNoContent());

        // Silent 204 either way so the endpoint cannot be used to probe for tokens — but the
        // device must survive.
        assertThat(deviceTokenRepository.findByToken("not-yours")).isPresent();
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void listsOnlyYourOwnDevices() throws Exception {
        DeviceToken theirs = new DeviceToken();
        theirs.setToken("theirs");
        theirs.setAccountId(CARER);
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
