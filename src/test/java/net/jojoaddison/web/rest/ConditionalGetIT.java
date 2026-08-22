package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.IntegrationTest;
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

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aStaleValidatorGetsTheBodyRatherThanA304() throws Exception {
        restMockMvc
            .perform(get("/api/patients").header(HttpHeaders.IF_NONE_MATCH, "\"not-the-current-etag\""))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.ETAG));
    }
}
