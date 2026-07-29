package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.domain.enumeration.ShiftType;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
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
 * WP6 gate (professional-onboarding-workflow.md § Duty roster + step 11):
 * assignment-only policy enforced server-side, professionals read their own
 * assignments, and the first-login acknowledgement writes an idempotent
 * OnboardingEvent.
 */
@AutoConfigureMockMvc
@IntegrationTest
class DutyRosterFlowIT {

    private static final String PRO = "nurse-pro";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private OnboardingEventRepository eventRepository;

    private Profile profile;

    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(new Profile().accountId(PRO).firstName("Nur").lastName("Se"));
    }

    @AfterEach
    void cleanup() {
        dutyRosterRepository.deleteAll();
        profileRepository.deleteAll();
        applicationRepository.deleteAll();
        eventRepository.deleteAll();
    }

    private String assignmentJson() {
        return (
            "{\"date\":\"" +
            LocalDate.now().plusDays(1) +
            "\",\"duty\":\"NURSE\",\"professionalId\":\"" +
            profile.getId() +
            "\",\"shift\":\"NIGHT\",\"name\":\"Ward 3 night\"}"
        );
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void adminAssignsListsAndUnassigns() throws Exception {
        restMockMvc
            .perform(post("/api/onboarding/duty-rosters").contentType(MediaType.APPLICATION_JSON).content(assignmentJson()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.shift").value("NIGHT"));
        restMockMvc.perform(get("/api/onboarding/duty-rosters")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        // unknown professional rejected
        restMockMvc
            .perform(
                post("/api/onboarding/duty-rosters")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignmentJson().replace(profile.getId(), "no-such-profile"))
            )
            .andExpect(status().isBadRequest());

        String id = dutyRosterRepository.findAll().get(0).getId();
        restMockMvc.perform(delete("/api/onboarding/duty-rosters/" + id)).andExpect(status().isNoContent());
        assertThat(dutyRosterRepository.count()).isZero();
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void professionalsCannotAssignOrListAll() throws Exception {
        restMockMvc
            .perform(post("/api/onboarding/duty-rosters").contentType(MediaType.APPLICATION_JSON).content(assignmentJson()))
            .andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/onboarding/duty-rosters")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void professionalReadsOwnAssignmentsOnly() throws Exception {
        dutyRosterRepository.save(
            new DutyRoster()
                .date(LocalDate.now())
                .duty(DutyRole.NURSE)
                .professionalId(profile.getId())
                .shift(ShiftType.MORNING)
                .name("Ward 3")
        );
        dutyRosterRepository.save(
            new DutyRoster()
                .date(LocalDate.now())
                .duty(DutyRole.DOCTOR)
                .professionalId("someone-else")
                .shift(ShiftType.NIGHT)
                .name("Ward 9")
        );
        restMockMvc
            .perform(get("/api/onboarding/duty-rosters/my"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Ward 3"));
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void firstLoginAcknowledgementIsRecordedOnceAsAnEvent() throws Exception {
        ProfessionalApplication application = applicationRepository.save(
            new ProfessionalApplication().accountId(PRO).status(OnboardingStatus.ACTIVE)
        );

        restMockMvc
            .perform(get("/api/onboarding/acknowledgement"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acknowledged").value(false));
        restMockMvc.perform(post("/api/onboarding/acknowledgement")).andExpect(status().isCreated());
        restMockMvc
            .perform(get("/api/onboarding/acknowledgement"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acknowledged").value(true));
        // idempotence: second acknowledgement conflicts, no duplicate event
        restMockMvc.perform(post("/api/onboarding/acknowledgement")).andExpect(status().isConflict());
        assertThat(eventRepository.findByApplicationIdOrderByAtAsc(application.getId())).hasSize(1);
    }

    @Test
    @WithMockUser(username = "no-application", authorities = { "ROLE_USER" })
    void accountsWithoutApplicationsHaveNothingToAcknowledge() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/acknowledgement"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acknowledged").value(true));
    }
}
