package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Visit;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.PatientServiceClient;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The patient day plan, and the two 403s that have to be indistinguishable.
 *
 * <h2>What this endpoint is</h2>
 *
 * <p>{@code GET /api/duty-roster/customer/{customerId}} answers "who is visiting me, and when",
 * from {@code visits[].customerId}. hc-patient's portal reaches it through that stack's gateway.
 * It replaced two endpoints that could not answer the question: hc-patient's own {@code DutyRoster},
 * which had no date and no patient id, and hc-admin's, which took its subject from the path and
 * checked nothing about who was asking.
 *
 * <h2>The pair of 403s is the assertion, not two assertions</h2>
 *
 * <p>An unauthorised caller and an unknown customer must be refused <b>identically</b>. If they are
 * not, the endpoint answers "does this customer exist" for every id anyone cares to try — to any
 * authenticated caller on any of the three stacks, since they share a signing key. So the cases
 * below do not merely assert 403 twice; {@link #anUnknownCustomerIsRefusedIdenticallyToSomebodyElses}
 * compares the two responses field by field and fails if they differ in status, body or content
 * type.
 *
 * <h2>How identity gets here</h2>
 *
 * <p>The token's {@code email} claim, resolved through patientservice to a {@code patientId}. The
 * client is mocked, as it is in {@code RosterTrailIT} — what is under test is the rule, not the
 * transport, and a test that reached a sibling stack would report on what was running on the
 * machine. The {@code jwt()} post-processor is used rather than {@code @WithMockUser} because the
 * claim only exists on a real {@code Jwt} principal: a mock user has no claims, which is also
 * exactly what a token from <em>this</em> stack's gateway looks like, and
 * {@link #aClinicianTokenWithNoEmailClaimIsRefused} pins that.
 */
@AutoConfigureMockMvc
@IntegrationTest
class CustomerDayPlanIT {

    private static final String API = "/api/duty-roster/customer/{customerId}";

    private static final String ME = "patient-me";
    private static final String SOMEBODY_ELSE = "patient-somebody-else";
    private static final String NOBODY = "patient-nobody-has-this-id";

    private static final String MY_EMAIL = "me@abofonsa.care";
    private static final String SPACE = "space-osu";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @MockitoBean
    private PatientServiceClient patientServiceClient;

    private Profile clinician;

    @BeforeEach
    void setUp() {
        cleanup();
        clinician = profileRepository.save(new Profile().accountId("nurse-1").firstName("Ama").lastName("Boateng"));

        // One round, two customers on it. That the round is shared is the point of the fixture:
        // the response must carry my visit and must not carry the other person's.
        dutyRosterRepository.save(
            new DutyRoster()
                .date(LocalDate.now().plusDays(1))
                .duty(DutyRole.NURSE)
                .professionalId(clinician.getId())
                .shift(ShiftType.DAY)
                .name("Morning round")
                .geographicSpaceId(SPACE)
                .visits(
                    new ArrayList<>(
                        List.of(
                            new Visit().id("v-mine").customerId(ME).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0)),
                            new Visit().id("v-theirs").customerId(SOMEBODY_ELSE).startTime(LocalTime.of(11, 0)).endTime(LocalTime.of(12, 0))
                        )
                    )
                )
        );

        // The identity hop: this email is this patient, and nobody else's address resolves at all.
        when(patientServiceClient.profileByEmail(anyString())).thenReturn(Optional.empty());
        when(patientServiceClient.profileByEmail(MY_EMAIL)).thenReturn(Optional.of(profile(ME)));
    }

    @AfterEach
    void cleanup() {
        dutyRosterRepository.deleteAll();
        profileRepository.deleteAll();
    }

    private static PatientProfile profile(String patientId) {
        return new PatientProfile("profile-" + patientId, patientId, "Kojo", null, "Mensah", null, null, null, null, null, null, null);
    }

    /** A patient token: a real Jwt principal carrying the email claim hc-patient's gateway mints. */
    private static JwtRequestPostProcessor patient(String email) {
        return jwt().jwt(token -> token.subject("a-login").claim("email", email));
    }

    // --- the answer -------------------------------------------------------------------------------

    @Test
    void aPatientReadsTheirOwnDayPlan() throws Exception {
        restMockMvc
            .perform(get(API, ME).with(patient(MY_EMAIL)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$[0].startTime").value("09:00:00"))
            .andExpect(jsonPath("$[0].shift").value("DAY"))
            .andExpect(jsonPath("$[0].professionalName").value("Ama Boateng"))
            .andExpect(jsonPath("$[0].geographicSpaceId").value(SPACE));
    }

    /**
     * The other customer on the same round is not in the response, and neither is their id.
     *
     * <p>Asserted by <b>absence</b>, over the whole document with a deep scan, because a projection
     * applied to the first element only would satisfy any positive assertion. Returning the round
     * whole would tell one patient who else their clinician is seeing that morning.
     */
    @Test
    void aDayPlanCarriesNoOtherCustomerFromTheSameRound() throws Exception {
        String body = restMockMvc
            .perform(get(API, ME).with(patient(MY_EMAIL)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$..customerId").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain(SOMEBODY_ELSE);
        assertThat(body).doesNotContain("11:00");
    }

    // --- the two refusals, and their sameness ------------------------------------------------------

    @Test
    void askingAboutSomebodyElseIsForbidden() throws Exception {
        restMockMvc.perform(get(API, SOMEBODY_ELSE).with(patient(MY_EMAIL))).andExpect(status().isForbidden());
    }

    @Test
    void askingAboutAnUnknownCustomerIsForbidden() throws Exception {
        restMockMvc.perform(get(API, NOBODY).with(patient(MY_EMAIL))).andExpect(status().isForbidden());
    }

    /**
     * <b>The case this endpoint exists to survive.</b>
     *
     * <p>{@code SOMEBODY_ELSE} is a real customer on a real round; {@code NOBODY} is an id that
     * appears nowhere in the collection. The two responses are compared in full — status, content
     * type and body — so a future change that made one a 404, or gave one a different message, fails
     * here rather than in production, where the symptom would be a working screen and an oracle.
     */
    @Test
    void anUnknownCustomerIsRefusedIdenticallyToSomebodyElses() throws Exception {
        MvcResult somebodyElse = restMockMvc.perform(get(API, SOMEBODY_ELSE).with(patient(MY_EMAIL))).andReturn();
        MvcResult nobody = restMockMvc.perform(get(API, NOBODY).with(patient(MY_EMAIL))).andReturn();

        assertThat(nobody.getResponse().getStatus())
            .as("an unknown customer and somebody else's must be the same status")
            .isEqualTo(somebodyElse.getResponse().getStatus())
            .isEqualTo(403);
        assertThat(nobody.getResponse().getContentAsString())
            .as("...and the same body, or the difference is the oracle")
            .isEqualTo(somebodyElse.getResponse().getContentAsString())
            .isEqualTo("Not your day plan");
        assertThat(nobody.getResponse().getContentType()).isEqualTo(somebodyElse.getResponse().getContentType());
        assertThat(nobody.getResponse().getHeaderNames()).isEqualTo(somebodyElse.getResponse().getHeaderNames());
    }

    /**
     * A token this stack's own gateway minted carries no {@code email} claim, so its holder is not a
     * customer and is refused — with the same 403 as everybody else, and without a special case.
     */
    @Test
    void aClinicianTokenWithNoEmailClaimIsRefused() throws Exception {
        restMockMvc
            .perform(get(API, ME).with(jwt().jwt(token -> token.subject("nurse-1"))))
            .andExpect(status().isForbidden())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo("Not your day plan"));
    }

    /**
     * An unreachable patient stack means the caller cannot be resolved, and an unresolved caller is
     * refused rather than served.
     *
     * <p>{@code PatientServiceClient} degrades to empty by contract, and every other consumer in this
     * service reads that as "no data". Here it has to read as "no identity": serving a day plan to
     * somebody who might not be the patient because a sibling was down is not a degradation anybody
     * would accept. The refusal is the same one, so an outage discloses nothing either.
     */
    @Test
    void anUnreachablePatientStackRefusesRatherThanServes() throws Exception {
        when(patientServiceClient.profileByEmail(anyString())).thenReturn(Optional.empty());

        restMockMvc
            .perform(get(API, ME).with(patient(MY_EMAIL)))
            .andExpect(status().isForbidden())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo("Not your day plan"));
    }

    /** Anonymous is a 401 from the chain, before any of the above runs. */
    @Test
    void anonymousIsUnauthorised() throws Exception {
        restMockMvc.perform(get(API, ME)).andExpect(status().isUnauthorized());
    }
}
