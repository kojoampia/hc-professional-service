package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Profile;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The order {@code GET /api/duty-roster/all} pages in (docs/duty-roster.md § 8, backlog item 7).
 *
 * <p>Item 7 bounded the estate read to a {@code Page} and gave it {@code Sort.by("date", "shift")} so
 * that paging was over a defined order rather than an unspecified one. Both halves of that were
 * wrong, and neither could be seen from a test of one page:
 *
 * <ul>
 *   <li><b>Ascending was backwards.</b> The estate accumulates history and is never pruned, so page 0
 *       of an ascending read is the oldest assignments it holds. An administrator creating a round
 *       for tomorrow saw the list refresh onto twenty rounds from months ago with the new one nine
 *       pages away — a correct write that reads exactly like a lost one. Unpaginated it could not
 *       happen, because everything arrived at once and the administrator could scroll.
 *   <li><b>{@code (date, shift)} is not unique.</b> Every professional rostered on the same date and
 *       shift ties, which is the ordinary shape of an estate rather than an edge of it, and no
 *       ordering is promised among tied documents across two separate queries. A page boundary
 *       falling inside a tie group could repeat a row on page 2 or drop it — precisely the failure
 *       the sort was added to prevent.
 * </ul>
 *
 * <p><b>The tie group is stored in reverse id order deliberately.</b> Insertion order is close enough
 * to Mongo's natural order that a tie group written id-ascending would come back id-ascending with no
 * tiebreaker at all, and every assertion here would pass against the ordering it is meant to reject.
 * Writing it backwards is what makes these tests capable of failing: without {@code id} as the final
 * key the group returns in the order it was inserted, which is the order this class asserts against.
 *
 * <p>{@code DutyRosterVisitPrivacyIT} owns the paging headers and the projection; this owns the
 * ordering, and asserts it across <em>every</em> page rather than the first.
 */
@AutoConfigureMockMvc
@IntegrationTest
class DutyRosterEstateOrderIT {

    private static final String PRO = "order-nurse";

    /** Today is the middle of the estate: rounds exist behind it and one ahead of it. */
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    /** The date every tie-group round shares, so only the tiebreaker can order them. */
    private static final LocalDate TIE_DATE = TODAY.minusDays(10);

    /** Ids of the tie group in the order a stable ordering must return them: ascending. */
    private static final List<String> TIE_IDS = List.of("tie-a", "tie-b", "tie-c", "tie-d", "tie-e");

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private ProfileRepository profileRepository;

    /** Never reached: nothing here opens a day, and an unreachable sibling would only add noise. */
    @MockitoBean
    private PatientServiceClient patientServiceClient;

    private Profile profile;

    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(new Profile().accountId(PRO).firstName("Or").lastName("Der"));
        org.mockito.Mockito.when(patientServiceClient.profiles()).thenReturn(List.of());

        // Reverse id order, so natural order and id order disagree — see the note on the class.
        for (String id : TIE_IDS.reversed()) {
            store(id, TIE_DATE, "Shared ward");
        }
    }

    @AfterEach
    void cleanup() {
        dutyRosterRepository.deleteAll();
        profileRepository.deleteAll();
    }

    private void store(String id, LocalDate date, String name) {
        dutyRosterRepository.save(
            new DutyRoster()
                .id(id)
                .date(date)
                .duty(DutyRole.NURSE)
                .professionalId(profile.getId())
                .shift(ShiftType.DAY)
                .name(name)
                .visits(new ArrayList<>())
        );
    }

    /** History behind the tie group: one round a day going backwards, oldest last. */
    private void storeHistory(int days) {
        for (int day = 0; day < days; day++) {
            store("history-" + day, TODAY.minusDays(30L + day), "Ward " + day);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> idsOnPage(int page, int size, String... sort) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/duty-roster/all")
            .param("page", String.valueOf(page))
            .param("size", String.valueOf(size));
        for (String order : sort) {
            request = request.param("sort", order);
        }
        String body = restMockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return List.copyOf((List<String>) JsonPath.read(body, "$[*].id"));
    }

    /**
     * Every page, walked to exhaustion and concatenated.
     *
     * <p>The whole point of the exercise: a repeat or a skip lives at a page <em>boundary</em>, so a
     * test that reads one page can no more see it than the administrator could.
     */
    private List<String> everyPage(int size, String... sort) throws Exception {
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 50; page++) {
            List<String> ids = idsOnPage(page, size, sort);
            if (ids.isEmpty()) {
                return seen;
            }
            seen.addAll(ids);
        }
        throw new AssertionError("the estate read never ran out of pages");
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void putsTheRoundJustCreatedOnTheFirstPageInsteadOfMonthsOfHistory() throws Exception {
        // The concrete failure: 25 historic rounds, an administrator creates one for tomorrow, and the
        // list refreshes onto page 0. Ascending, page 0 is 20 rounds from a month ago and the new one
        // is two pages away; the screen says the create did not take.
        storeHistory(25);
        store("brand-new", TOMORROW, "Ward 3");

        List<String> firstPage = idsOnPage(0, 20);

        assertThat(firstPage).contains("brand-new");
        assertThat(firstPage).first().isEqualTo("brand-new");
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void ordersEveryPageNewestDateFirst() throws Exception {
        storeHistory(10);
        store("brand-new", TOMORROW, "Ward 3");

        List<String> everything = everyPage(3);

        // Read the dates back in the order the pages produced them and assert they never increase.
        List<LocalDate> dates = everything.stream().map(id -> dutyRosterRepository.findById(id).orElseThrow().getDate()).toList();
        assertThat(dates).isSortedAccordingTo((left, right) -> right.compareTo(left));
        assertThat(everything).startsWith("brand-new");
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void pagesATieGroupInAStableOrderRatherThanWhicheverOrderMongoHasToday() throws Exception {
        // Five rounds sharing a date and a shift, stored in reverse id order. With no tiebreaker the
        // response is whatever the collection happens to yield — here, the order they went in.
        List<String> everything = everyPage(2);

        assertThat(everything).containsExactlyElementsOf(TIE_IDS);
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void neverRepeatsOrSkipsARowAcrossAPageBoundaryInsideATieGroup() throws Exception {
        // A page size that cannot help but split the tie group: 5 tied rounds at 2 a page, so the
        // boundary falls inside it twice.
        storeHistory(3);

        List<String> everything = everyPage(2);

        assertThat(everything).doesNotHaveDuplicates();
        assertThat(everything).hasSize(8);
        // And the same rows, in the same order, when the same collection is paged differently — a
        // total order does not depend on where the boundaries land.
        assertThat(everyPage(3)).containsExactlyElementsOf(everything);
        assertThat(everyPage(100)).containsExactlyElementsOf(everything);
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void keepsACallerNamedSortAndStillBreaksItsTiesById() throws Exception {
        // The default is not the only non-unique ordering an administrator can ask for; `name` is one
        // they might. The caller's key still decides, and id only decides what it left undecided.
        store("aa-newest", TOMORROW, "Zzz ward");

        List<String> everything = everyPage(2, "name,asc");

        assertThat(everything).containsExactly("tie-a", "tie-b", "tie-c", "tie-d", "tie-e", "aa-newest");
    }
}
