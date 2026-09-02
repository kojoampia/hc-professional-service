package net.jojoaddison.web.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
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
import net.jojoaddison.service.PatientServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The customer snapshot must not leave on a roster read (docs/duty-roster.md § 6, backlog item 7).
 *
 * <p>{@code DutyRosterVisitPrivacyTest} guards {@code toString} — the leak that needs somebody to log
 * the object. This guards the one that needs nobody at all: the HTTP response. Until item 7,
 * {@code GET /api/duty-roster} and {@code GET /api/duty-roster/all} both serialised the stored
 * {@link DutyRoster}, so every customer name, address and phone number on the round went out with it.
 * Three comments in the repository said the roster disclosed no patient detail while it did, and two
 * of them cited a {@code DutyRosterVisitPrivacyIT} that did not exist. This is that class.
 *
 * <p><b>Every assertion here is an absence, and that is the point.</b> Asserting that the fields a
 * projection <em>should</em> carry are present proves nothing about the ones it should not: a
 * serialiser change, a widened DTO or a resource quietly returning the entity again would leave every
 * positive assertion passing. So the snapshot is written into the fixture, given values no other
 * field could produce, and each read is asserted to have nowhere for them —
 * {@code jsonPath(...).doesNotExist()} on the exact stored field names.
 *
 * <p>The day read is asserted in the <em>opposite</em> direction in the same class, deliberately. It
 * is the one read that must disclose (§ 6, DR6), and a privacy test that only ever demands absence
 * would be satisfied by breaking it — the clinician standing at the door with no address is the other
 * way to fail this design.
 */
@AutoConfigureMockMvc
@IntegrationTest
class DutyRosterVisitPrivacyIT {

    private static final String PRO = "privacy-nurse";
    private static final String CUSTOMER = "patient-7";

    /** Values nothing else in a roster response could plausibly produce, so a hit is unambiguous. */
    private static final String NAME = "Akosua Mensah";
    private static final String ADDRESS = "GA-123-4567, 5 Ankobra River Street, Osu, Greater Accra";
    private static final String PHONE = "0244000111";

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private ProfileRepository profileRepository;

    /**
     * Stubbed to answer nothing, so the day read's refresh cannot overwrite the fixture's snapshot
     * with a different one. An unreachable sibling serves the stored snapshot (DR6), which is exactly
     * the fixture these tests wrote.
     */
    @MockitoBean
    private PatientServiceClient patientServiceClient;

    private Profile profile;

    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(new Profile().accountId(PRO).firstName("Pri").lastName("Vacy"));
        org.mockito.Mockito.when(patientServiceClient.profiles()).thenReturn(List.of());
        store(TOMORROW, "Ward 3");
    }

    @AfterEach
    void cleanup() {
        dutyRosterRepository.deleteAll();
        profileRepository.deleteAll();
    }

    private void store(LocalDate date, String name) {
        Visit visit = new Visit().customerId(CUSTOMER).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0));
        visit.setCustomerName(NAME);
        visit.setCustomerAddress(ADDRESS);
        visit.setCustomerPhone(PHONE);
        dutyRosterRepository.save(
            new DutyRoster()
                .date(date)
                .duty(DutyRole.NURSE)
                .professionalId(profile.getId())
                .shift(ShiftType.DAY)
                .name(name)
                .visits(new ArrayList<>(List.of(visit)))
        );
    }

    // ------------------------------------------------- the caller's own roster

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void theOwnRosterReadCarriesNoCustomerSnapshot() throws Exception {
        restMockMvc
            .perform(get("/api/duty-roster"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            // The identifiers and times stay — the calendar counts them and reassignment names them,
            // and this is the same line clearSnapshot() and the 90-day purge draw.
            .andExpect(jsonPath("$[0].visits[0].customerId").value(CUSTOMER))
            .andExpect(jsonPath("$[0].visits[0].startTime").exists())
            // ...and the three snapshot fields are not in the body at all.
            .andExpect(jsonPath("$[0].visits[0].customerName").doesNotExist())
            .andExpect(jsonPath("$[0].visits[0].customerAddress").doesNotExist())
            .andExpect(jsonPath("$[0].visits[0].customerPhone").doesNotExist());
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void theUnboundedOwnRosterReadIsTheOneThatMattersAndCarriesNoSnapshotEither() throws Exception {
        // Omitting from/to returns the whole roster and is what the dashboard asks for on every load,
        // so this is the shape the leak actually took in production rather than an edge of it. A
        // second round on another date makes "the whole roster" mean more than one row.
        store(TOMORROW.plusDays(40), "Ward 9");

        restMockMvc
            .perform(get("/api/duty-roster"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            // Asserted across every round and every visit rather than at [0], so a projection applied
            // to only the first element could not pass.
            .andExpect(jsonPath("$..customerName").doesNotExist())
            .andExpect(jsonPath("$..customerAddress").doesNotExist())
            .andExpect(jsonPath("$..customerPhone").doesNotExist());
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void theRangeNarrowedReadCarriesNoSnapshot() throws Exception {
        restMockMvc
            .perform(get("/api/duty-roster").param("from", TOMORROW.toString()).param("to", TOMORROW.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            // A different finder runs for a bounded range than for an unbounded one, so the projection
            // has to hold on both paths — it is applied at the resource for exactly that reason.
            .andExpect(jsonPath("$..customerName").doesNotExist())
            .andExpect(jsonPath("$..customerAddress").doesNotExist())
            .andExpect(jsonPath("$..customerPhone").doesNotExist());
    }

    // --------------------------------------------------------- the estate read

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void theEstateReadCarriesNoCustomerSnapshot() throws Exception {
        restMockMvc
            .perform(get("/api/duty-roster/all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].visits[0].customerId").value(CUSTOMER))
            .andExpect(jsonPath("$..customerName").doesNotExist())
            .andExpect(jsonPath("$..customerAddress").doesNotExist())
            .andExpect(jsonPath("$..customerPhone").doesNotExist());
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void theEstateReadIsPagedAndCountsTheWholeCollection() throws Exception {
        store(TOMORROW.plusDays(40), "Ward 9");
        store(TOMORROW.plusDays(80), "Ward 12");

        restMockMvc
            .perform(get("/api/duty-roster/all").param("page", "0").param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            // X-Total-Count is the collection's count, not the size of the list in the body — the
            // distinction GET /api/patients still gets wrong, where the header is just list.size().
            .andExpect(header().string("X-Total-Count", "3"))
            .andExpect(header().exists("Link"))
            // Default sort is date then shift, so paging is over a defined order and page 2 can
            // neither repeat nor skip a row from page 1.
            .andExpect(jsonPath("$[0].name").value("Ward 3"))
            .andExpect(jsonPath("$[1].name").value("Ward 9"));

        restMockMvc
            .perform(get("/api/duty-roster/all").param("page", "1").param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Ward 12"))
            .andExpect(header().string("X-Total-Count", "3"));
    }

    // ------------------------------------------------- the deliberate exception

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void theDayReadStillCarriesTheSnapshotBecauseSomebodyIsAboutToWalkToTheAddress() throws Exception {
        // The other way to fail § 6. A privacy rule that only ever demanded absence would be satisfied
        // by a day view showing a visit with no address, which is a clinician standing in the street.
        restMockMvc
            .perform(get("/api/duty-roster/day/" + TOMORROW))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].visits[0].customerName").value(NAME))
            .andExpect(jsonPath("$[0].visits[0].customerAddress").value(ADDRESS))
            .andExpect(jsonPath("$[0].visits[0].customerPhone").value(PHONE));
    }

    @Test
    @WithMockUser(username = PRO, authorities = { "ROLE_NURSE" })
    void theYearSummaryCarriesNoCustomerAtAll() throws Exception {
        // Not merely no snapshot: no customer id and no visit objects either, only a count. That is
        // what makes a year of it safe to hold in a browser while the day read is one day at a time.
        restMockMvc
            .perform(get("/api/duty-roster/summary").param("year", String.valueOf(TOMORROW.getYear())))
            .andExpect(status().isOk())
            // `visits` here is a count, not a list — there is no visit object to carry anything.
            .andExpect(jsonPath("$[0].visits").value(1))
            .andExpect(jsonPath("$..customerName").doesNotExist())
            .andExpect(jsonPath("$..customerAddress").doesNotExist())
            .andExpect(jsonPath("$..customerPhone").doesNotExist())
            .andExpect(jsonPath("$..customerId").doesNotExist());
    }
}
