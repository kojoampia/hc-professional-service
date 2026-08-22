package net.jojoaddison.web.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link PatientResource} (web-mobile-port.md § Phase 1.1).
 *
 * <p><b>What these can and cannot cover.</b> The directory is the union of this service's tasks and
 * patientservice's cases, and {@code PatientServiceClient} fails soft — an unreachable sibling
 * yields an empty list rather than an error. There is no patientservice in an integration test, so
 * the directory here is always empty. That makes these tests about the <em>wiring</em>: that paging
 * headers are emitted at all, that an unsortable property is refused, and that the endpoint is
 * authenticated. The row-level behaviour — match counts, filters, the sort whitelist — is covered
 * by {@code PatientDirectoryServiceUnitTest}, where the sibling can be stubbed.
 *
 * <p>Worth stating because the reverse mistake is easy: asserting an empty body here and calling it
 * proof that the filter works.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PatientResourceIT {

    private static final String NURSE = "patients-nurse";

    @Autowired
    private MockMvc restMockMvc;

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aPageCarriesTheJHipsterPagingHeaders() throws Exception {
        // X-Total-Count used to be list.size(), which agreed with the body by construction. It is
        // now the match count, and Link comes with it — the same pair ProfileResource emits.
        restMockMvc
            .perform(get("/api/patients?page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "0"))
            .andExpect(header().exists("Link"));
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void anUnsortablePropertyIs400_andSaysWhatIsSortable() throws Exception {
        restMockMvc
            .perform(get("/api/patients?sort=dropTable,asc"))
            .andExpect(status().isBadRequest())
            .andExpect(result -> {
                String body = result.getResponse().getContentAsString();
                org.assertj.core.api.Assertions.assertThat(body).contains("dropTable").contains("patientName");
            });
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aWhitelistedSortIsAccepted() throws Exception {
        restMockMvc.perform(get("/api/patients?sort=lastActivityAt,desc")).andExpect(status().isOk());
    }

    /**
     * A read-only role reads.
     *
     * <p>{@code /api/patients/**} is deliberately NOT hoisted above the CLINICAL_MUTATION rules in
     * {@code SecurityConfiguration} — GETs fall through to {@code /api/**} authenticated, which is
     * what lets a carer see their own caseload while still being refused clinical writes.
     */
    @Test
    @WithMockUser(username = "patients-carer", authorities = { "ROLE_CARER" })
    void aReadOnlyRoleCanReadTheDirectory() throws Exception {
        restMockMvc.perform(get("/api/patients")).andExpect(status().isOk());
    }

    @Test
    void anAnonymousCallerIsRejected() throws Exception {
        restMockMvc.perform(get("/api/patients")).andExpect(status().isUnauthorized());
    }
}
