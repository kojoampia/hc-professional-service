package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Absence;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Visit;
import net.jojoaddison.domain.enumeration.AbsenceStatus;
import net.jojoaddison.domain.enumeration.AbsenceType;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;
import net.jojoaddison.repository.AbsenceRepository;
import net.jojoaddison.repository.DutyRosterRepository;
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
 * Requesting and granting time off (docs/duty-roster.md § 8, DR4).
 *
 * <p>Three rules carry the package and each has a case here that would be easy to ship without: that
 * a professional cannot backdate but an administrator can, because <b>sickness is reported after it
 * starts</b> and otherwise the commonest real case has nowhere to go; that approval is refused while
 * the days are still rostered, so cover is arranged rather than discovered; and that a request is
 * visible to its requester and to administrators and to nobody else.
 *
 * <p>There is a fourth, and it is the one that would have shipped broken: <b>every clinical role can
 * ask for leave</b>, including the four that are read-only under {@code CLINICAL_MUTATION}. Booking
 * time off is not a clinical mutation, and without an explicit matcher a carer would have been
 * unable to request a holiday.
 */
@AutoConfigureMockMvc
@IntegrationTest
class AbsenceResourceIT {

    private static final String NURSE = "absence-nurse";
    private static final String CARER = "absence-carer";
    private static final LocalDate FROM = LocalDate.now().plusDays(10);
    private static final LocalDate TO = LocalDate.now().plusDays(12);

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private AbsenceRepository absenceRepository;

    @Autowired
    private DutyRosterRepository dutyRosterRepository;

    @Autowired
    private ProfileRepository profileRepository;

    private Profile nurse;
    private Profile carer;

    @BeforeEach
    void setUp() {
        cleanup();
        nurse = profileRepository.save(new Profile().accountId(NURSE).firstName("Ab").lastName("Sent"));
        carer = profileRepository.save(new Profile().accountId(CARER).firstName("Ca").lastName("Rer"));
    }

    @AfterEach
    void cleanup() {
        absenceRepository.deleteAll();
        dutyRosterRepository.deleteAll();
        profileRepository.deleteAll();
    }

    private static String absenceJson(LocalDate from, LocalDate to, String type) {
        return "{\"fromDate\":\"%s\",\"toDate\":\"%s\",\"type\":\"%s\"}".formatted(from, to, type);
    }

    private Absence stored(Profile who, LocalDate from, LocalDate to, AbsenceStatus status) {
        return absenceRepository.save(
            new Absence().professionalId(who.getId()).fromDate(from).toDate(to).type(AbsenceType.HOLIDAY).status(status)
        );
    }

    private void roster(Profile who, LocalDate date) {
        dutyRosterRepository.save(
            new DutyRoster()
                .date(date)
                .duty(DutyRole.NURSE)
                .professionalId(who.getId())
                .shift(ShiftType.DAY)
                .name("Ward 3")
                .visits(new ArrayList<>(List.of(new Visit().customerId("c-1").startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0)))))
        );
    }

    // ------------------------------------------------------------ requesting

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aProfessionalRequestsTheirOwnLeaveAsRequested() throws Exception {
        restMockMvc
            .perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON).content(absenceJson(FROM, TO, "HOLIDAY")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.professionalId").value(nurse.getId()));
    }

    @Test
    @WithMockUser(username = CARER, authorities = { "ROLE_CARER" })
    void aReadOnlyClinicalRoleCanStillAskForTimeOff() throws Exception {
        // The security-config trap, asserted. CLINICAL_MUTATION is admin/doctor/nurse/paramedic/
        // pharmacist/therapist, so under the bare POST /api/** rule a carer, care angel, chemist or
        // technician could not have requested a holiday — a silent 403 with nothing to point at.
        // /api/absences/** is registered .authenticated() above that rule for exactly this reason.
        restMockMvc
            .perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON).content(absenceJson(FROM, TO, "HOLIDAY")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.professionalId").value(carer.getId()));
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aProfessionalCannotBackdate() throws Exception {
        restMockMvc
            .perform(
                post("/api/absences").contentType(MediaType.APPLICATION_JSON).content(absenceJson(LocalDate.now().minusDays(1), TO, "SICK"))
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void anAdministratorRecordsRetrospectiveSickness() throws Exception {
        // The path that has to exist. Sickness is reported after it begins — usually by phone, at
        // 06:00 — so somebody must be able to enter a day that has already started, and grant it in
        // the same action.
        restMockMvc
            .perform(
                post("/api/absences")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"professionalId\":\"%s\",\"fromDate\":\"%s\",\"toDate\":\"%s\",\"type\":\"SICK\",\"status\":\"APPROVED\"}".formatted(
                                nurse.getId(),
                                LocalDate.now().minusDays(2),
                                LocalDate.now()
                            )
                    )
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.professionalId").value(nurse.getId()));
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aProfessionalCannotFileLeaveForSomebodyElseOrPreApproveTheirOwn() throws Exception {
        // Both fields are ignored rather than rejected: the absence is forced onto the caller and to
        // REQUESTED, the same way OnboardingService force-sets accountId. A client does not get to
        // decide whose absence this is or that it is already granted.
        restMockMvc
            .perform(
                post("/api/absences")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"professionalId\":\"%s\",\"fromDate\":\"%s\",\"toDate\":\"%s\",\"type\":\"HOLIDAY\",\"status\":\"APPROVED\"}".formatted(
                                carer.getId(),
                                FROM,
                                TO
                            )
                    )
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.professionalId").value(nurse.getId()))
            .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void rejectsARangeThatEndsBeforeItStarts() throws Exception {
        restMockMvc
            .perform(post("/api/absences").contentType(MediaType.APPLICATION_JSON).content(absenceJson(TO, FROM, "HOLIDAY")))
            .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------- approving

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void approvesWhenTheDaysAreFree() throws Exception {
        Absence absence = stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);

        restMockMvc
            .perform(put("/api/absences/" + absence.getId() + "/approve"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void refusesApprovalWhileTheDaysAreStillRosteredAndNamesTheRounds() throws Exception {
        Absence absence = stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);
        roster(nurse, FROM.plusDays(1));

        restMockMvc
            .perform(put("/api/absences/" + absence.getId() + "/approve"))
            .andExpect(status().isConflict())
            // Naming the rounds is the point: cover gets arranged rather than discovered by whoever
            // opens the roster next, and the administrator has something to click through to.
            .andExpect(jsonPath("$.conflictingRosterIds.length()").value(1));

        assertThat(absenceRepository.findById(absence.getId()).orElseThrow().getStatus()).isEqualTo(AbsenceStatus.REQUESTED);
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void reassigningTheRoundUnblocksApproval() throws Exception {
        // The whole loop, which is what the 409 exists to make possible: refused, cover arranged,
        // the same request retried unchanged and granted.
        Absence absence = stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);
        roster(nurse, FROM.plusDays(1));
        String rosterId = dutyRosterRepository.findAll().get(0).getId();

        restMockMvc.perform(put("/api/absences/" + absence.getId() + "/approve")).andExpect(status().isConflict());

        restMockMvc
            .perform(put("/api/duty-roster/" + rosterId + "/reassign").param("professionalId", carer.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.professionalId").value(carer.getId()))
            // The visits go with it — they are a coherent plan, not a set of loose appointments.
            .andExpect(jsonPath("$.visits.length()").value(1));

        restMockMvc
            .perform(put("/api/absences/" + absence.getId() + "/approve"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void refusesApprovalForANightRoundStartingTheDayBefore() throws Exception {
        // The wrap, in the one place a date range meets a shift outside the overlap validator. A
        // NIGHT round dated the eve of the absence runs 23:00 until 07:00 on its first day, so it is
        // worked almost entirely inside leave that has been granted. Asking only for rounds dated
        // within the range reads perfectly and misses it.
        Absence absence = stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);
        dutyRosterRepository.save(
            new DutyRoster()
                .date(FROM.minusDays(1))
                .duty(DutyRole.NURSE)
                .professionalId(nurse.getId())
                .shift(ShiftType.NIGHT)
                .name("Nights")
        );

        restMockMvc
            .perform(put("/api/absences/" + absence.getId() + "/approve"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.conflictingRosterIds.length()").value(1));
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void aDayRoundTheDayBeforeDoesNotBlockApproval() throws Exception {
        // The other half of the same rule, and the reason it is a filter rather than a wider range:
        // a DAY round on the eve finishes at 15:00, before the leave begins, and blocking on it
        // would refuse an absence nobody is working through.
        Absence absence = stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);
        roster(nurse, FROM.minusDays(1));

        restMockMvc
            .perform(put("/api/absences/" + absence.getId() + "/approve"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void approvingTwiceIsNotAnError() throws Exception {
        Absence absence = stored(nurse, FROM, TO, AbsenceStatus.APPROVED);

        // Two administrators reaching the same conclusion, not a conflict. Note this also means an
        // already-granted absence is not re-checked against the roster — cover may legitimately have
        // been arranged around it since.
        restMockMvc.perform(put("/api/absences/" + absence.getId() + "/approve")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void aProfessionalCannotApproveTheirOwnLeave() throws Exception {
        Absence absence = stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);

        restMockMvc.perform(put("/api/absences/" + absence.getId() + "/approve")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ visibility

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void seesOnlyTheirOwnAbsences() throws Exception {
        stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);
        stored(carer, FROM, TO, AbsenceStatus.APPROVED);

        restMockMvc.perform(get("/api/absences")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        // /all is the administrator's estate view, exactly as with the duty roster.
        restMockMvc.perform(get("/api/absences/all")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void cannotReadAColleaguesAbsenceById() throws Exception {
        Absence theirs = stored(carer, FROM, TO, AbsenceStatus.APPROVED);

        // Sick leave is health information about a colleague. Same 403 as an id that does not exist.
        restMockMvc.perform(get("/api/absences/" + theirs.getId())).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/absences/no-such-id")).andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------- range read

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void theRangeReadReturnsAnAbsenceThatMerelyOverlapsIt() throws Exception {
        // Starts well before the window and runs into it. "fromDate inside the range" would drop it
        // silently, and the calendar would draw an ordinary working week over granted leave.
        stored(nurse, FROM.minusDays(30), FROM.plusDays(1), AbsenceStatus.APPROVED);
        stored(nurse, FROM.plusDays(60), FROM.plusDays(62), AbsenceStatus.REQUESTED);

        restMockMvc
            .perform(get("/api/absences").param("from", FROM.toString()).param("to", TO.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void anOpenEndedRangeIsOpenRatherThanEmpty() throws Exception {
        // The MIN/MAX sentinel trap, asserted from the outside. BSON cannot compare those years, so
        // substituting them and reusing the two-sided query returns nothing at all — an empty
        // calendar rather than an error. A missing bound picks a different finder instead.
        stored(nurse, FROM.minusDays(30), FROM.minusDays(28), AbsenceStatus.APPROVED);
        stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);

        restMockMvc
            .perform(get("/api/absences").param("from", FROM.minusDays(1).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        restMockMvc
            .perform(get("/api/absences").param("to", FROM.minusDays(1).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void cannotReadAColleaguesAbsencesByNamingThem() throws Exception {
        stored(carer, FROM, TO, AbsenceStatus.APPROVED);

        // A 403, not an empty list: the two answers mean different things and collapsing the first
        // into the second hides an authorization failure behind a plausible blank week.
        restMockMvc
            .perform(get("/api/absences").param("professionalId", carer.getId()).param("from", FROM.toString()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void anAdministratorReadsAnothersAbsencesForTheApprovalQueue() throws Exception {
        stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);

        restMockMvc
            .perform(get("/api/absences").param("professionalId", nurse.getId()).param("from", FROM.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void anAdministratorSeesTheWholeEstate() throws Exception {
        stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);
        stored(carer, FROM, TO, AbsenceStatus.APPROVED);

        restMockMvc.perform(get("/api/absences/all")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    // ------------------------------------------------------------ withdrawal

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void withdrawsTheirOwnPendingRequestButNotAGrantedOne() throws Exception {
        Absence pending = stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);
        restMockMvc.perform(delete("/api/absences/" + pending.getId())).andExpect(status().isNoContent());

        Absence granted = stored(nurse, FROM, TO, AbsenceStatus.APPROVED);
        // Cover may already have been arranged around it, so coming back is a conversation with the
        // roster administrator rather than a button.
        restMockMvc.perform(delete("/api/absences/" + granted.getId())).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void cannotWithdrawAColleaguesRequest() throws Exception {
        Absence theirs = stored(carer, FROM, TO, AbsenceStatus.REQUESTED);

        restMockMvc.perform(delete("/api/absences/" + theirs.getId())).andExpect(status().isForbidden());
        assertThat(absenceRepository.count()).isOne();
    }

    @Test
    @WithMockUser(username = "admin", authorities = { "ROLE_ADMIN" })
    void anAdministratorDeclinesByDeleting() throws Exception {
        // There is no REJECTED status: a declined absence goes, so the calendar never shows a day
        // nobody can read. See AbsenceStatus.
        Absence pending = stored(nurse, FROM, TO, AbsenceStatus.REQUESTED);

        restMockMvc.perform(delete("/api/absences/" + pending.getId())).andExpect(status().isNoContent());
        assertThat(absenceRepository.count()).isZero();
    }

    // ---------------------------------------------------------- year summary

    @Test
    @WithMockUser(username = NURSE, authorities = { "ROLE_NURSE" })
    void theYearSummaryCarriesAbsenceAndSurvivesADayThatIsBoth() throws Exception {
        LocalDate day = LocalDate.of(2026, 3, 4);
        absenceRepository.save(
            new Absence()
                .professionalId(nurse.getId())
                .fromDate(day)
                .toDate(day.plusDays(1))
                .type(AbsenceType.HOLIDAY)
                .status(AbsenceStatus.REQUESTED)
        );
        roster(nurse, day);

        restMockMvc
            .perform(get("/api/duty-roster/summary").param("year", "2026"))
            .andExpect(status().isOk())
            // Two days: the rostered-and-requested one, and the absence's second day, which has no
            // round at all and appears only because DR4 made an absent day something.
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].date").value("2026-03-04"))
            .andExpect(jsonPath("$[0].absence.type").value("HOLIDAY"))
            .andExpect(jsonPath("$[0].absence.status").value("REQUESTED"))
            // Neither field suppresses the other: leave asked for over a shift still assigned is
            // exactly the day an administrator needs to see.
            .andExpect(jsonPath("$[0].shifts.length()").value(1))
            .andExpect(jsonPath("$[1].date").value("2026-03-05"))
            .andExpect(jsonPath("$[1].shifts.length()").value(0))
            .andExpect(jsonPath("$[1].absence.type").value("HOLIDAY"));
    }
}
