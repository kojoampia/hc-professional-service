package net.jojoaddison.web.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Visit;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.DutyRosterService;
import net.jojoaddison.service.PatientServiceClient;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may read a customer's activity trail (docs/duty-roster.md § 7, DR3).
 *
 * <p>This is an authorization test with a data-shaping test attached, and the balance is deliberate:
 * the trail's <em>contents</em> are one filter and a sort, while its <em>boundary</em> is the only
 * thing standing between a signed-in clinician and any patient in the platform.
 *
 * <p>The boundary is the caller's own roster within ±30 days, recomputed on every read. The plan
 * originally put it in a JWT claim minted at sign-in; the owner chose the server-side check on
 * 2026-08-21, because {@code professionalservice} owns the roster and serves the trail, so nothing
 * cross-stack was ever involved. The cases below are the ones that decision has to survive.
 */
@AutoConfigureMockMvc
@IntegrationTest
class RosterTrailIT {

    private static final String PRO = "trail-nurse";
    private static final String OTHER_PRO = "other-nurse";
    private static final String MINE = "patient-mine";
    private static final String THEIRS = "patient-theirs";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @MockitoBean
    private PatientServiceClient patientServiceClient;

    private Profile mine;

    @BeforeEach
    void setUp() {
        cleanup();
        mine = profileRepository.save(new Profile().accountId(PRO).firstName("Tra").lastName("Il"));
        Profile theirs = profileRepository.save(new Profile().accountId(OTHER_PRO).firstName("Oth").lastName("Er"));

        // Mine today; theirs today. Same day, different professionals — the point being that the
        // boundary is per-clinician, not per-date.
        round(mine.getId(), LocalDate.now(), MINE);
        round(theirs.getId(), LocalDate.now(), THEIRS);

        when(patientServiceClient.activityLogs()).thenReturn(
            List.of(
                log("a-recent", MINE, "Blood pressure checked", 2),
                log("a-older", MINE, "Dressing changed", 5),
                log("a-stale", MINE, "Admitted", 30),
                log("a-theirs", THEIRS, "Not yours", 1)
            )
        );
    }

    @AfterEach
    void cleanup() {
        dutyRosterRepository.deleteAll();
        profileRepository.deleteAll();
    }

    private void round(String professionalId, LocalDate date, String customerId) {
        dutyRosterRepository.save(
            new DutyRoster()
                .date(date)
                .duty(DutyRole.NURSE)
                .professionalId(professionalId)
                .shift(ShiftType.DAY)
                .name("Round")
                .visits(
                    new ArrayList<>(List.of(new Visit().customerId(customerId).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0))))
                )
        );
    }

    /**
     * A fixture in the shape patientservice ACTUALLY sends.
     *
     * <p>It used to be built as {@code (id, patientId, name, description, Instant)} — the shape this
     * service wished for rather than the one the sibling emits. That is why this suite stayed green
     * while every real trail came back empty: the test and the production DTO agreed with each other
     * and both disagreed with patientservice. Fixture shapes copied from the consumer are worth
     * nothing; these are copied from {@code hc-patient/api/.../domain/ActivityLog.java}.
     */
    private static ActivityLog log(String id, String patientId, String summary, int daysAgo) {
        return new ActivityLog(
            id,
            patientId,
            "case-1",
            Instant.now().minus(daysAgo, ChronoUnit.DAYS),
            summary,
            "detail",
            "OBSERVATION",
            "CLINICIAN",
            "professional-1",
            LocalDate.now().minusDays(daysAgo)
        );
    }

    private static String trail(String customerId) {
        return "/api/duty-roster/customers/" + customerId + "/trail";
    }

    // ------------------------------------------------------------- entitlement

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void readsTheTrailOfACustomerOnTheirOwnRound() throws Exception {
        restMockMvc
            .perform(get(trail(MINE)))
            .andExpect(status().isOk())
            // Two of the three: the 30-day-old entry is outside the 7-day trail window. Newest first.
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("a-recent"))
            .andExpect(jsonPath("$[1].id").value("a-older"));
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void refusesACustomerOnSomeoneElsesRound() throws Exception {
        // The core case. THEIRS is a real customer, rostered today, with activity — everything except
        // being on this clinician's roster.
        restMockMvc.perform(get(trail(THEIRS))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void refusesAnUnknownCustomerTheSameWayAsAnUnauthorisedOne() throws Exception {
        // Same status, same shape. Distinguishing the two would turn this endpoint into a way to ask
        // "does this id exist" for every id in the platform.
        restMockMvc.perform(get(trail("no-such-customer"))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void refusesAnAdministratorWithNoRosterOfTheirOwn() throws Exception {
        // ROLE_ADMIN opens the whole-estate roster and every onboarding review screen, and still does
        // not open this: the boundary is the roster, not the role. An administrator who needs a
        // patient's history has the patient record surfaces for it, under their own rules.
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "stranger-from-another-stack", authorities = { "ROLE_DOCTOR" })
    void refusesATokenFromASiblingStack() throws Exception {
        // professionalservice accepts tokens minted by the hc-admin and hc-patient gateways — one
        // signing key across three stacks. Such a caller authenticates perfectly well here and has no
        // Profile, so the roster set is empty and the read fails closed with no coordination between
        // the three stacks and nothing anyone has to remember.
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isForbidden());
    }

    @Test
    void refusesAnUnauthenticatedCaller() throws Exception {
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- the window

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void entitlementFollowsTheRosterInAndOutOfTheThirtyDayWindow() throws Exception {
        dutyRosterRepository.deleteAll();

        // Just inside, in the past: a round last month still lets its customer's trail be reviewed.
        round(mine.getId(), LocalDate.now().minusDays(DutyRosterService.TRAIL_WINDOW_DAYS), MINE);
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isOk());

        // Just outside: the window is rolling, so entitlement expires by itself. Nobody revokes it.
        dutyRosterRepository.deleteAll();
        round(mine.getId(), LocalDate.now().minusDays(DutyRosterService.TRAIL_WINDOW_DAYS + 1), MINE);
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isForbidden());

        // And forward, so an upcoming round can be prepared for.
        dutyRosterRepository.deleteAll();
        round(mine.getId(), LocalDate.now().plusDays(DutyRosterService.TRAIL_WINDOW_DAYS), MINE);
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void losingTheRosterEntryRevokesTheTrailImmediately() throws Exception {
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isOk());

        dutyRosterRepository.deleteAll();

        // No token turnover, no cache to expire, no claim to go stale — the next request is already
        // refused. This is the property the server-side check was chosen for.
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isForbidden());
    }

    /**
     * A rest day widens nobody's entitlement — asserted rather than reasoned about.
     *
     * <p>{@code trailCustomerIds} widens by {@code visits[]}, and an {@code OFF} round has none, so
     * this holds by construction rather than by a filter on the shift. That is the stronger property
     * and the reason no filter was added there. But "contributes nothing" and "was never reached" are
     * indistinguishable from a green build, and the trail query is one of the three places
     * `backlog.md` item 9 named as never having been exercised with a value meaning "no window" — so
     * the construction is pinned here rather than left as an argument in a comment.
     */
    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void anOffDayGrantsNoTrailEntitlement() throws Exception {
        dutyRosterRepository.deleteAll();
        dutyRosterRepository.save(
            new DutyRoster().date(LocalDate.now()).duty(DutyRole.NURSE).professionalId(mine.getId()).shift(ShiftType.OFF).name("Rest day")
        );

        // The clinician is rostered today and has read this customer's trail on other days; today
        // they are off, the round carries no visits, and the entitlement set is empty.
        restMockMvc.perform(get(trail(MINE))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------- degradation

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void answersAnEmptyTrailWhenThePatientStackIsUnreachable() throws Exception {
        // PatientServiceClient degrades to empty by contract. The authorization half is local, so it
        // still works — the clinician is told nothing happened rather than being locked out, and the
        // visit's own time and address, which is what they need at the door, come from this service.
        when(patientServiceClient.activityLogs()).thenReturn(List.of());

        restMockMvc.perform(get(trail(MINE))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void toleratesAnActivityEntryWithNoDate() throws Exception {
        when(patientServiceClient.activityLogs()).thenReturn(
            List.of(
                new ActivityLog("a-undated", MINE, "case-1", null, "No date", "detail", "OBSERVATION", "CLINICIAN", "professional-1", null),
                log("a-recent", MINE, "Dated", 1)
            )
        );

        // Dropped rather than thrown on or sorted arbitrarily: an entry with no time cannot be placed
        // in a 7-day window, and a NullPointerException on the day view is the worse answer.
        restMockMvc
            .perform(get(trail(MINE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("a-recent"));
    }
}
