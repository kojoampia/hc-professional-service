package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Conditional GET on the clinician read endpoints (web-mobile-port.md § Phase 1.2).
 *
 * <p>The point of the feature is a phone on mobile data that polls a roster and a caseload: an
 * unchanged read should cost headers rather than a payload. The point of <em>this</em> test is the
 * boundary — that the filter is registered on the endpoints it was meant for and, more importantly,
 * <b>not</b> on {@code /api/duty-roster/day/{date}}, which writes on its read path.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ConditionalGetIT {

    private static final String NURSE = "etag-nurse";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private ProfileRepository profileRepository;

    /** The ETag of a GET, or null when the filter is not registered on that path. */
    private String etagOf(String path) throws Exception {
        MvcResult result = restMockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return result.getResponse().getHeader(HttpHeaders.ETAG);
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void anUnchangedPatientDirectoryCostsHeadersRatherThanAPayload() throws Exception {
        String etag = etagOf("/api/patients?page=0&size=20");
        assertThat(etag).as("the filter must be registered on /api/patients").isNotBlank();

        restMockMvc
            .perform(get("/api/patients?page=0&size=20").header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void theDashboardSummaryRevalidatesToo() throws Exception {
        String etag = etagOf("/api/dashboard/summary");
        assertThat(etag).isNotBlank();

        restMockMvc.perform(get("/api/dashboard/summary").header(HttpHeaders.IF_NONE_MATCH, etag)).andExpect(status().isNotModified());
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void theOwnRosterRevalidates() throws Exception {
        String etag = etagOf("/api/duty-roster");
        assertThat(etag).isNotBlank();

        restMockMvc.perform(get("/api/duty-roster").header(HttpHeaders.IF_NONE_MATCH, etag)).andExpect(status().isNotModified());
    }

    /**
     * The exclusion that matters.
     *
     * <p>{@code /api/duty-roster/day/{date}} refreshes visit snapshots as it reads. An ETag there
     * would advertise it as cacheable and invite a client to skip the call — which is precisely the
     * call that performs the write. If someone later "tidies" the filter's patterns into
     * {@code /api/duty-roster/*}, this fails.
     */
    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void theDayViewIsNOTcacheable_becauseItWritesOnTheReadPath() throws Exception {
        MvcResult result = restMockMvc.perform(get("/api/duty-roster/day/2026-08-22")).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
    }

    /**
     * A rostered rest day invalidates a cached roster, like any other row.
     *
     * <p>The ETag include list is one of the three places {@code backlog.md} item 9 named as never
     * having been exercised with a shift value that means "no window", and this is what that check
     * comes to. The filter is shallow — it hashes the bytes the endpoint produced — so the whole
     * correctness argument is "{@code OFF} rounds are in the response, therefore the hash moves".
     * Both halves are load-bearing and only one is visible from {@code WebConfigurer}: an endpoint
     * that quietly omitted {@code OFF} would keep serving 304 to a phone whose clinician had just
     * been given a rest day, with nothing in the filter, the resource or a status assertion noticing.
     * A test that only asked "does this path carry an ETag" cannot see that either, which is why this
     * one changes the data and compares two validators.
     */
    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void rosteringAnOffDayInvalidatesTheCachedRoster() throws Exception {
        Profile profile = profileRepository.save(new Profile().accountId(NURSE).firstName("Eta").lastName("Gee"));
        try {
            String before = etagOf("/api/duty-roster");
            assertThat(before).isNotBlank();

            dutyRosterRepository.save(
                new DutyRoster()
                    .date(LocalDate.now().plusDays(1))
                    .duty(DutyRole.NURSE)
                    .professionalId(profile.getId())
                    .shift(ShiftType.OFF)
                    .name("Rest day")
            );

            // Not merely "the ETag changed": the previously valid validator must now miss, which is
            // the behaviour a polling handset actually depends on.
            restMockMvc
                .perform(get("/api/duty-roster").header(HttpHeaders.IF_NONE_MATCH, before))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG));

            assertThat(etagOf("/api/duty-roster")).isNotEqualTo(before);
        } finally {
            dutyRosterRepository.deleteAll();
            profileRepository.deleteAll();
        }
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aStaleValidatorGetsTheBodyRatherThanA304() throws Exception {
        restMockMvc
            .perform(get("/api/patients").header(HttpHeaders.IF_NONE_MATCH, "\"not-the-current-etag\""))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.ETAG));
    }
}
