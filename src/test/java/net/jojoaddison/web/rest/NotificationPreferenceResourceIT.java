package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link NotificationPreferenceResource} (MOB10).
 */
@IntegrationTest
@AutoConfigureMockMvc
class NotificationPreferenceResourceIT {

    private static final String NURSE = "prefs-nurse";
    private static final String CARER = "prefs-carer";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        profileRepository.deleteAll();
    }

    private static String body(boolean messages, boolean compliance, boolean showSenderName) {
        return "{\"messages\":%s,\"compliance\":%s,\"showSenderName\":%s}".formatted(messages, compliance, showSenderName);
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void anAccountWithNoProfileGetsTheDefaults() throws Exception {
        // 200 with defaults rather than 404: the client is asking what would happen if a
        // notification arrived now, and that is well defined before onboarding completes.
        restMockMvc
            .perform(get("/api/notifications/preferences"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages").value(true))
            .andExpect(jsonPath("$.compliance").value(true))
            // The one that defaults OFF: a lock screen is visible to anyone holding the phone.
            .andExpect(jsonPath("$.showSenderName").value(false));
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void writingPreferencesForAnAccountWithNoProfileCreatesOne() throws Exception {
        restMockMvc
            .perform(put("/api/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(body(false, true, true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages").value(false))
            .andExpect(jsonPath("$.showSenderName").value(true));

        // A toggle that flips back on the next visit is worse than no toggle, so the write cannot
        // simply be dropped for a clinician who has not finished onboarding.
        assertThat(profileRepository.findByAccountId(NURSE)).isPresent();
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aWritePreservesTheRestOfTheProfile() throws Exception {
        Profile existing = new Profile().accountId(NURSE).firstName("Ama").lastName("Mensah").mobilePhone("+233200000000");
        profileRepository.save(existing);

        restMockMvc
            .perform(put("/api/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(body(false, false, false)))
            .andExpect(status().isOk());

        // The whole reason this is not routed through PUT /api/onboarding/profile, which sets every
        // field it knows from the body it is given.
        Profile saved = profileRepository.findByAccountId(NURSE).orElseThrow();
        assertThat(saved.getFirstName()).isEqualTo("Ama");
        assertThat(saved.getMobilePhone()).isEqualTo("+233200000000");
        assertThat(saved.getPushMessagesEnabled()).isFalse();
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void whatIsWrittenIsWhatIsReadBack() throws Exception {
        restMockMvc
            .perform(put("/api/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(body(true, false, true)))
            .andExpect(status().isOk());

        restMockMvc
            .perform(get("/api/notifications/preferences"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages").value(true))
            .andExpect(jsonPath("$.compliance").value(false))
            .andExpect(jsonPath("$.showSenderName").value(true));
    }

    /**
     * The security trap, again.
     *
     * <p>{@code PUT /api/**} requires {@code CLINICAL_MUTATION}. Anywhere but under
     * {@code /api/notifications/**} a carer, angel, chemist or technician — every read-only role —
     * would get a silent 403 turning their own notifications off, and would have no way to act on it.
     */
    @Test
    @WithMockUser(username = CARER, authorities = { "ROLE_CARER" })
    void aReadOnlyRoleCanStillChangeItsOwnPreferences() throws Exception {
        restMockMvc
            .perform(put("/api/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(body(false, false, false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages").value(false));
    }

    @Test
    void anAnonymousCallerIsRejected() throws Exception {
        restMockMvc.perform(get("/api/notifications/preferences")).andExpect(status().isUnauthorized());
    }
}
