package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.PatientServiceClient;
import net.jojoaddison.service.PatientServiceUnavailableException;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link PatientResource} (web-mobile-port.md § Phase 1.1).
 *
 * <p><b>What these can and cannot cover.</b> The directory is the union of this service's tasks and
 * patientservice's cases, and there is no patientservice in an integration test, so the sibling is
 * mocked and the directory here is empty unless a case says otherwise. That makes most of these tests
 * about the <em>wiring</em>: that paging headers are emitted at all, that an unsortable property is
 * refused, and that the endpoint is authenticated. The row-level behaviour — match counts, filters,
 * the sort whitelist — is covered by {@code PatientDirectoryServiceUnitTest}.
 *
 * <p>Worth stating because the reverse mistake is easy: asserting an empty body here and calling it
 * proof that the filter works.
 *
 * <p><b>The exception is the outage pair at the bottom</b>, which is a wiring test of exactly the kind
 * that belongs here: backlog item 24 is about a signal that has to survive two layers of
 * {@code catch} and an {@code Optional} to reach a clinician as a 503 rather than as "this patient is
 * not in your caseload". Proving it in a unit test proves only that the service raises.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PatientResourceIT {

    private static final String NURSE = "patients-nurse";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    /**
     * The sibling, mocked so that "unreachable" can be staged.
     *
     * <p>Its unstubbed reads answer empty lists — Mockito's default for a collection — which is the
     * same thing the real client answered here before, so every case above this one is unaffected.
     */
    @MockitoBean
    private PatientServiceClient patientServiceClient;

    @BeforeEach
    void setUp() {
        cleanup();
        // The nurse needs a profile, or every read short-circuits before the sibling is consulted and
        // the outage cases below would pass for the wrong reason.
        profileRepository.save(new Profile().accountId(accountIdFor(NURSE)).firstName("Pat").lastName("Nurse"));
    }

    @AfterEach
    void cleanup() {
        profileRepository.deleteAll();
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
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
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
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
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
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
    @WithMockGatewayUser(login = "patients-carer", authorities = { "ROLE_CARER" })
    void aReadOnlyRoleCanReadTheDirectory() throws Exception {
        restMockMvc.perform(get("/api/patients")).andExpect(status().isOk());
    }

    @Test
    void anAnonymousCallerIsRejected() throws Exception {
        restMockMvc.perform(get("/api/patients")).andExpect(status().isUnauthorized());
    }

    // --- Writes (web-mobile-port.md § Phase 1.3) ----------------------------------------------

    private static final String ACTIVITY = "{\"title\":\"Wound dressed\",\"description\":\"No exudate\"}";
    private static final String REPORT = "{\"name\":\"assessment.pdf\",\"reportType\":\"ASSESSMENT\"}";

    /**
     * The mapping exists at all.
     *
     * <p>Before Phase 1.3 both of these returned 404 <em>from dispatch</em> — no such handler — while
     * web/ had been POSTing to them for months. A nurse holds CLINICAL_MUTATION, so security passes
     * and the 404 now comes from the entitlement check instead: this test account has no caseload.
     * Same status, entirely different reason, which is why the carer case below matters.
     */
    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void aClinicianReachesTheActivityHandlerAndIsRefusedOnlyByTheCaseload() throws Exception {
        restMockMvc
            .perform(post("/api/patients/not-my-patient/activities").contentType(MediaType.APPLICATION_JSON).content(ACTIVITY))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void aClinicianReachesTheReportHandlerToo() throws Exception {
        restMockMvc
            .perform(post("/api/patients/not-my-patient/reports").contentType(MediaType.APPLICATION_JSON).content(REPORT))
            .andExpect(status().isNotFound());
    }

    /**
     * The authorization split, and the reason /api/patients/** was left OUT of the hoisted prefixes.
     *
     * <p>A carer may read a record and may not file into one. Four prefixes in SecurityConfiguration
     * are hoisted above the CLINICAL_MUTATION rules — onboarding, messaging, notifications, absences
     * — each to let read-only roles do something that is not a clinical mutation. Filing an
     * observation IS one. If someone hoists this prefix "for consistency", these two fail.
     */
    @Test
    @WithMockGatewayUser(login = "patients-carer", authorities = { "ROLE_CARER" })
    void aREADONLYroleCannotFileAnActivity() throws Exception {
        restMockMvc
            .perform(post("/api/patients/any/activities").contentType(MediaType.APPLICATION_JSON).content(ACTIVITY))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockGatewayUser(login = "patients-carer", authorities = { "ROLE_CARER" })
    void aREADONLYroleCannotFileAReport() throws Exception {
        restMockMvc
            .perform(post("/api/patients/any/reports").contentType(MediaType.APPLICATION_JSON).content(REPORT))
            .andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousCallerCannotFileAnything() throws Exception {
        restMockMvc
            .perform(post("/api/patients/any/activities").contentType(MediaType.APPLICATION_JSON).content(ACTIVITY))
            .andExpect(status().isUnauthorized());
    }

    // --- An archived case is readable through the detail endpoint (backlog.md item 82) ----------

    /**
     * The promise, through the HTTP layer: 200, the clinical prose, and {@code archivedAt} on the wire.
     *
     * <p>Asserted here as well as in the unit test because two things only this layer can show. The
     * resource catches {@code PatientNotInCaseloadException} and answers {@code notFound()} with no
     * body, so anything that reintroduces the refusal reads as a 404 to a client and as nothing at all
     * to a log. And {@code archivedAt} is a field a client cannot use unless it is serialised — a
     * record component that Jackson never emits would satisfy every assertion in the unit test.
     */
    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void anARCHIVEDcaseIsServedByTheDetailEndpoint() throws Exception {
        String professionalId = profileRepository.findByAccountId(accountIdFor(NURSE)).orElseThrow().getId();
        when(patientServiceClient.casesIncludingArchived("p-1")).thenReturn(List.of(archivedCase("c-old", "p-1", professionalId)));

        restMockMvc
            .perform(get("/api/patients/p-1/cases/c-old"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("c-old"))
            .andExpect(jsonPath("$.diagnosis").value("a diagnosis"))
            .andExpect(jsonPath("$.archivedAt").value("2026-08-21T09:00:00Z"));
    }

    /**
     * And the refusal it must not become: the same archived case, a caller with no tie to the patient.
     *
     * <p>Staged with the case assigned to somebody else, so the read succeeded and the row exists —
     * the only thing standing between this endpoint and any archived case in the estate is the
     * entitlement rule.
     */
    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void anARCHIVEDcaseIsStill404ForACallerOutsideTheCaseload() throws Exception {
        when(patientServiceClient.casesIncludingArchived("p-1")).thenReturn(List.of(archivedCase("c-old", "p-1", "another-professional")));

        restMockMvc.perform(get("/api/patients/p-1/cases/c-old")).andExpect(status().isNotFound());
    }

    private static ClinicalCase archivedCase(String id, String patientId, String assignedTo) {
        return new ClinicalCase(
            id,
            patientId,
            1,
            "Title",
            Instant.parse("2026-08-20T09:00:00Z"),
            null,
            "brief",
            "CLOSED",
            "symptoms",
            "a diagnosis",
            assignedTo,
            null,
            Instant.parse("2026-08-21T09:00:00Z")
        );
    }

    // --- An outage is a 503, not a caseload decision (backlog.md item 24) ---------------------

    private static PatientServiceUnavailableException outage() {
        return PatientServiceUnavailableException.read(
            "/api/clinical-cases",
            PatientServiceUnavailableException.Fault.TRANSPORT,
            "connection refused"
        );
    }

    /**
     * The one that hurt: reading a patient during a sibling outage said <b>404, not your patient</b>.
     *
     * <p>A clinician was told the person in front of them was not in their caseload, and an
     * administrator reading the same screen concluded the assignment was missing rather than that a
     * service was down. 503 is the truthful answer and the operationally useful one — it is the
     * difference between "call the office about your caseload" and "wait five minutes".
     *
     * <p>Asserted here rather than only in the unit test because the signal has to survive
     * {@code record()}'s {@code Optional} and the resource's {@code orElseThrow}: widening the
     * {@code catch} in {@link PatientResource} would put the 404 back with nothing else failing.
     */
    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void readingAPatientDuringAnOutageIs503_notNotFound() throws Exception {
        // The scoped read, because a patient record asks about one patient since backlog item 23. The
        // directory case below still stages the estate-wide one, and the pair is worth reading
        // together: the two surfaces genuinely make different requests now.
        when(patientServiceClient.clinicalCases(anyString())).thenThrow(outage());

        restMockMvc.perform(get("/api/patients/some-patient")).andExpect(status().isServiceUnavailable());
    }

    /**
     * The 404 it must not be confused with: the sibling answered, and this patient is not the
     * caller's. Both cases sit here together because the fix is only worth anything if the two
     * statuses differ.
     */
    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void readingAPatientTrulyOutsideTheCaseloadIsStill404() throws Exception {
        restMockMvc.perform(get("/api/patients/some-patient")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void theDirectoryDuringAnOutageIs503_notAnEmptyCaseload() throws Exception {
        // An empty page with X-Total-Count: 0 reads as "you have no patients", which is a statement
        // this service cannot make while it cannot see half of the union it computes.
        when(patientServiceClient.clinicalCases()).thenThrow(outage());

        restMockMvc.perform(get("/api/patients?page=0&size=20")).andExpect(status().isServiceUnavailable());
    }

    /**
     * And filing a note during an outage is 503 rather than the 404 that means "not your patient".
     *
     * <p>The write path routes its entitlement check through the same union, so it inherited the same
     * confusion — and here it told a clinician their observation had nowhere to go for a reason that
     * blamed the patient.
     */
    @Test
    @WithMockGatewayUser(login = NURSE, authorities = { "ROLE_NURSE" })
    void filingAnActivityDuringAnOutageIs503_notNotFound() throws Exception {
        when(patientServiceClient.clinicalCases(anyString())).thenThrow(outage());

        restMockMvc
            .perform(post("/api/patients/some-patient/activities").contentType(MediaType.APPLICATION_JSON).content(ACTIVITY))
            .andExpect(status().isServiceUnavailable());
    }
}
