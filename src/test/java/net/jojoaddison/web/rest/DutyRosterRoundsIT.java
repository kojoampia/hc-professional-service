package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
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
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.Address;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A shift is a round of visits (docs/duty-roster.md §§ 4–6, DR2): the write path with its validation
 * and customer snapshot, the range and year reads, and the 90-day privacy purge.
 *
 * <p>The window and overlap rules themselves are covered exhaustively and cheaply in
 * {@code DutyRosterServiceUnitTest}; what this proves is that they are actually wired to the
 * endpoint, that a rejected round is a <b>400 and not a 500</b>, and that the cross-stack snapshot
 * behaves the way the plan requires in both directions — filled when the patient stack answers, and
 * <em>silently skipped</em> when it does not, with the round still saved.
 */
@AutoConfigureMockMvc
@IntegrationTest
class DutyRosterRoundsIT {

    private static final String PRO = "round-nurse";
    private static final String CUSTOMER = "patient-7";
    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private ProfileRepository profileRepository;

    /**
     * The sibling stack is mocked, not reached. An integration test that depended on hc-patient being
     * up would fail for reasons that have nothing to do with this code, and the interesting cases
     * here are precisely the ones where it answers oddly or not at all.
     */
    @MockitoBean
    private PatientServiceClient patientServiceClient;

    private Profile profile;

    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(new Profile().accountId(PRO).firstName("Rou").lastName("Nd"));
        when(patientServiceClient.profiles()).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        dutyRosterRepository.deleteAll();
        profileRepository.deleteAll();
    }

    private String roundJson(String shift, String visits) {
        return """
        {"date":"%s","duty":"NURSE","professionalId":"%s","shift":"%s","name":"Ward 3","visits":[%s]}
        """.formatted(TOMORROW, profile.getId(), shift, visits);
    }

    private static String visitJson(String customerId, String start, String end) {
        return "{\"customerId\":\"%s\",\"startTime\":\"%s\",\"endTime\":\"%s\"}".formatted(customerId, start, end);
    }

    private static PatientProfile patientProfile() {
        return new PatientProfile(
            "profile-7",
            CUSTOMER,
            "Akosua",
            null,
            "Mensah",
            LocalDate.of(1990, 1, 1),
            "female",
            "0244000111",
            "0302000111",
            "akosua@example.com",
            null,
            new Address("addr-7", "GA-123-4567", "5 Ankobra River Street", "GA123", "Osu", null, "Ayawaso", "Greater Accra", "Ghana")
        );
    }

    // ------------------------------------------------------------- write path

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void storesARoundAndSnapshotsEachCustomerFromThePatientStack() throws Exception {
        when(patientServiceClient.profiles()).thenReturn(List.of(patientProfile()));

        restMockMvc
            .perform(
                post("/api/duty-roster")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(roundJson("DAY", visitJson(CUSTOMER, "09:00", "10:00")))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.visits.length()").value(1))
            .andExpect(jsonPath("$.visits[0].customerId").value(CUSTOMER))
            .andExpect(jsonPath("$.visits[0].customerName").value("Akosua Mensah"))
            // Digital address first: GhanaPostGPS is what actually navigates in Accra. District,
            // area code and country are left out — length without help.
            .andExpect(jsonPath("$.visits[0].customerAddress").value("GA-123-4567, 5 Ankobra River Street, Osu, Greater Accra"))
            // The mobile, not the landline — it is the number a clinician calls from the street.
            .andExpect(jsonPath("$.visits[0].customerPhone").value("0244000111"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void storesTheRoundWithIdsOnlyWhenThePatientStackIsUnreachable() throws Exception {
        // PatientServiceClient degrades to an empty list rather than throwing, so this is what an
        // hc-patient outage looks like from here. The round must still save: an administrator being
        // unable to write a roster because another stack is down is the failure worth avoiding, and
        // DR6's read-time refresh fills the snapshot on the next day-view open.
        when(patientServiceClient.profiles()).thenReturn(List.of());

        restMockMvc
            .perform(
                post("/api/duty-roster")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(roundJson("DAY", visitJson(CUSTOMER, "09:00", "10:00")))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.visits[0].customerId").value(CUSTOMER))
            .andExpect(jsonPath("$.visits[0].customerName").doesNotExist())
            .andExpect(jsonPath("$.visits[0].customerAddress").doesNotExist());

        assertThat(dutyRosterRepository.count()).isOne();
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void acceptsARoundWithNoVisits() throws Exception {
        restMockMvc
            .perform(post("/api/duty-roster").contentType(MediaType.APPLICATION_JSON).content(roundJson("DAY", "")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.visits.length()").value(0));
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void rejectsAVisitOutsideTheShiftWindowAsFourHundred() throws Exception {
        restMockMvc
            .perform(
                post("/api/duty-roster")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(roundJson("DAY", visitJson(CUSTOMER, "06:00", "08:00")))
            )
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("DAY")));

        assertThat(dutyRosterRepository.count()).isZero();
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void rejectsADoubleBookingAcrossMidnightAgainstARoundOnAnotherDate() throws Exception {
        // This one has to be an integration test, and it caught a real bug. The overlap check reads
        // the neighbouring dates because a NIGHT round runs into the next morning, and the finder
        // that fetches them was first written with Spring Data's `Between` — which in the MongoDB
        // module is EXCLUSIVE, so it returned only the target date and dropped both neighbours. The
        // mocked unit tests passed throughout: they stub the repository, so they prove the rule and
        // say nothing about the query that feeds it.
        store(
            TOMORROW,
            ShiftType.NIGHT,
            "Last night",
            new Visit().customerId("c-9").startTime(LocalTime.of(23, 0)).endTime(LocalTime.of(6, 0))
        );

        restMockMvc
            .perform(
                post("/api/duty-roster")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"date":"%s","duty":"NURSE","professionalId":"%s","shift":"FLEXIBLE","name":"Next day","visits":[%s]}
                        """.formatted(TOMORROW.plusDays(1), profile.getId(), visitJson("c-1", "05:00", "08:00"))
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("overlaps an existing assignment")));

        assertThat(dutyRosterRepository.count()).isOne();
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void rejectsADoubleBookingAgainstAnAlreadyStoredRound() throws Exception {
        restMockMvc
            .perform(
                post("/api/duty-roster")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(roundJson("DAY", visitJson("patient-1", "09:00", "12:00")))
            )
            .andExpect(status().isCreated());

        restMockMvc
            .perform(
                post("/api/duty-roster")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(roundJson("DAY", visitJson("patient-2", "11:00", "13:00")))
            )
            .andExpect(status().isBadRequest());

        assertThat(dutyRosterRepository.count()).isOne();
    }

    // -------------------------------------------------------------- range read

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void narrowsTheOwnRosterToADateRangeAndReturnsAllOfItWithoutOne() throws Exception {
        store(TOMORROW, ShiftType.DAY, "Early");
        store(TOMORROW.plusDays(5), ShiftType.DAY, "Middle");
        store(TOMORROW.plusDays(20), ShiftType.DAY, "Late");

        restMockMvc.perform(get("/api/duty-roster")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));

        restMockMvc
            .perform(get("/api/duty-roster").param("from", TOMORROW.plusDays(1).toString()).param("to", TOMORROW.plusDays(10).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Middle"));

        // Bounds are inclusive at both ends — "from the 1st to the 7th" includes the 7th.
        restMockMvc
            .perform(get("/api/duty-roster").param("from", TOMORROW.toString()).param("to", TOMORROW.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Early"));

        // One bound alone bounds that end only. Both directions are asserted because the tempting
        // implementation — a LocalDate.MIN/MAX sentinel through the two-sided query — returns an
        // empty list here rather than failing: BSON dates cannot represent year 999999999.
        restMockMvc
            .perform(get("/api/duty-roster").param("from", TOMORROW.plusDays(10).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Late"));

        restMockMvc
            .perform(get("/api/duty-roster").param("to", TOMORROW.plusDays(10).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("Early"))
            .andExpect(jsonPath("$[1].name").value("Middle"));
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void rejectsABackwardsRange() throws Exception {
        restMockMvc
            .perform(get("/api/duty-roster").param("from", TOMORROW.plusDays(5).toString()).param("to", TOMORROW.toString()))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ year summary

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void summarisesTheYearOneRecordPerRosteredDay() throws Exception {
        LocalDate day = LocalDate.of(2026, 3, 4);
        store(
            day,
            ShiftType.DAY,
            "Morning round",
            new Visit().customerId("c-1").startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0))
        );
        store(day, ShiftType.EVENING, "Evening round");
        store(LocalDate.of(2026, 6, 15), ShiftType.NIGHT, "Night");
        store(LocalDate.of(2025, 6, 15), ShiftType.NIGHT, "Previous year");

        restMockMvc
            .perform(get("/api/duty-roster/summary").param("year", "2026"))
            .andExpect(status().isOk())
            // Two entries, not 365: days with nothing on them are absent, and a year grid renders the
            // gaps as off. The 2025 round is excluded by the year bound.
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].date").value("2026-03-04"))
            .andExpect(jsonPath("$[0].shifts.length()").value(2))
            .andExpect(jsonPath("$[0].visits").value(1))
            .andExpect(jsonPath("$[1].date").value("2026-06-15"))
            .andExpect(jsonPath("$[1].visits").value(0));
    }

    @Test
    @WithMockUser(username = "stranger", authorities = { "ROLE_NURSE" })
    void answersEmptyForAnAccountWithNoProfile() throws Exception {
        store(TOMORROW, ShiftType.DAY, "Someone else's");

        // Having no profile is an ordinary state, not a failure — and it must not fall through to
        // somebody else's roster.
        restMockMvc.perform(get("/api/duty-roster")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        restMockMvc.perform(get("/api/duty-roster/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    // -------------------------------------------------------------- the purge

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void purgesSnapshotsPastRetentionAndKeepsTheCustomerId() throws Exception {
        LocalDate old = LocalDate.now().minusDays(DutyRosterService.SNAPSHOT_RETENTION_DAYS + 1);
        LocalDate recent = LocalDate.now().minusDays(DutyRosterService.SNAPSHOT_RETENTION_DAYS - 1);
        store(old, ShiftType.DAY, "Old round", snapshotted("c-old"));
        store(recent, ShiftType.DAY, "Recent round", snapshotted("c-recent"));

        restMockMvc
            .perform(post("/api/duty-roster/purge-snapshots"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roundsPurged").value(1))
            .andExpect(jsonPath("$.visitsPurged").value(1));

        Visit purged = roundNamed("Old round").getVisits().get(0);
        assertThat(purged.getCustomerId()).isEqualTo("c-old");
        assertThat(purged.getCustomerName()).isNull();
        assertThat(purged.getCustomerAddress()).isNull();
        assertThat(purged.getCustomerPhone()).isNull();

        // Inside the window, untouched — the clinician can still see who they visited last month.
        assertThat(roundNamed("Recent round").getVisits().get(0).getCustomerName()).isEqualTo("Kept");
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void purgingIsIdempotent() throws Exception {
        store(LocalDate.now().minusDays(200), ShiftType.DAY, "Ancient", snapshotted("c-1"));

        restMockMvc.perform(post("/api/duty-roster/purge-snapshots")).andExpect(jsonPath("$.visitsPurged").value(1));
        // Second run finds the snapshots already clear and rewrites nothing, so the nightly job and
        // an operator can both run it without doubling anything up.
        restMockMvc.perform(post("/api/duty-roster/purge-snapshots")).andExpect(jsonPath("$.visitsPurged").value(0));
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void purgingIsAdminOnly() throws Exception {
        restMockMvc.perform(post("/api/duty-roster/purge-snapshots")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private static Visit snapshotted(String customerId) {
        return new Visit()
            .customerId(customerId)
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .customerName("Kept")
            .customerAddress("GA-1, Somewhere")
            .customerPhone("024");
    }

    private void store(LocalDate date, ShiftType shift, String name, Visit... visits) {
        dutyRosterRepository.save(
            new DutyRoster()
                .date(date)
                .duty(DutyRole.NURSE)
                .professionalId(profile.getId())
                .shift(shift)
                .name(name)
                .visits(new java.util.ArrayList<>(List.of(visits)))
        );
    }

    private DutyRoster roundNamed(String name) {
        return dutyRosterRepository.findAll().stream().filter(round -> name.equals(round.getName())).findFirst().orElseThrow();
    }
}
