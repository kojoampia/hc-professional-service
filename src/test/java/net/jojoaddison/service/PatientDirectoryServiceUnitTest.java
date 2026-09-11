package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.PatientDirectoryService.DirectoryFilter;
import net.jojoaddison.service.dto.PatientDtos.CreateActivity;
import net.jojoaddison.service.dto.PatientDtos.CreateReport;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.Report;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The rules that decide whose patients a clinician sees.
 *
 * <p>Two of these matter more than the rest: that the directory is the <em>union</em> of task and
 * case assignments, since either alone silently hides half a caseload; and that a patient outside
 * that union cannot be read by id, since that is the only thing standing between this endpoint and
 * a way to read any patient in the platform.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientDirectoryServiceUnitTest {

    private static final String LOGIN = "dr.who";

    /**
     * The caller's gateway {@code User.id} — deliberately not the login, so a regression to
     * resolving the caller by {@code sub} fails here rather than passing (backlog.md item 50).
     */
    private static final String ACCOUNT_ID = "uid-dr.who";

    private static final String PROFESSIONAL_ID = "professional-1";

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private PatientServiceClient patientService;

    private InMemoryReceiptRepository receiptRepository;
    private PatientDirectoryService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(WithMockGatewayUser.Factory.authenticationFor(LOGIN, ACCOUNT_ID));
        Profile mine = new Profile();
        mine.setId(PROFESSIONAL_ID);
        mine.setAccountId(ACCOUNT_ID);
        when(profileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(mine));
        when(taskRepository.findByAttendantId(anyString())).thenReturn(List.of());
        when(patientService.clinicalCases()).thenReturn(List.of());
        when(patientService.profiles()).thenReturn(List.of());
        when(patientService.activityLogs()).thenReturn(List.of());
        when(patientService.medications()).thenReturn(List.of());
        when(patientService.reports()).thenReturn(List.of());
        // The scoped forms are stubbed separately and deliberately: an unstubbed one answers null and
        // NPEs, so a test that means to exercise a per-patient path cannot accidentally be served by
        // the estate-wide stub. That distinction is the subject of backlog item 23.
        when(patientService.clinicalCases(anyString())).thenReturn(List.of());
        // The archived-inclusive form is a sixth read and is stubbed here for the same reason: the
        // detail path is the only caller (backlog item 82), and a test that means to exercise it must
        // not be served by the stub for the live-only read beside it.
        when(patientService.casesIncludingArchived(anyString())).thenReturn(List.of());
        when(patientService.profiles(anyString())).thenReturn(List.of());
        when(patientService.activityLogs(anyString())).thenReturn(List.of());
        when(patientService.medications(anyString())).thenReturn(List.of());
        when(patientService.reports(anyString())).thenReturn(List.of());
        receiptRepository = new InMemoryReceiptRepository();
        service = new PatientDirectoryService(taskRepository, profileRepository, patientService, receiptRepository);
    }

    private Task task(String patientId) {
        Task task = new Task();
        task.setAttendantId(PROFESSIONAL_ID);
        task.setPatientId(patientId);
        return task;
    }

    private PatientProfile profile(String patientId, String first, String last, String sex, LocalDate born) {
        // Trailing null is the address, added to the record in DR2 for the duty-roster visit
        // snapshot. Nothing in this test reads it; the directory shows demographics, not doorsteps.
        return new PatientProfile(
            "profile-" + patientId,
            patientId,
            first,
            null,
            last,
            born,
            sex,
            "024",
            null,
            "p@example.com",
            null,
            null
        );
    }

    @Test
    void theDirectoryIsTheUnionOfTaskAndCaseAssignments() {
        // A clinician can be scheduled against a patient with no open case, and handed a case with
        // no scheduled task. Either source alone hides half the caseload.
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("patient-task")));
        when(patientService.clinicalCases()).thenReturn(
            List.of(
                new ClinicalCase(
                    "case-1",
                    "patient-case",
                    1,
                    "t",
                    Instant.now(),
                    null,
                    "brief",
                    "OPEN",
                    null,
                    null,
                    PROFESSIONAL_ID,
                    null,
                    null
                )
            )
        );
        when(patientService.profiles()).thenReturn(
            List.of(
                profile("patient-task", "Task", "Patient", "female", LocalDate.of(1990, 1, 1)),
                profile("patient-case", "Case", "Patient", "male", LocalDate.of(1980, 1, 1))
            )
        );

        assertThat(service.directory()).extracting("id").containsExactlyInAnyOrder("patient-task", "patient-case");
    }

    @Test
    void aCaseAssignedToSomeoneElseIsNotMine() {
        when(patientService.clinicalCases()).thenReturn(
            List.of(
                new ClinicalCase(
                    "case-1",
                    "patient-other",
                    1,
                    "t",
                    Instant.now(),
                    null,
                    "brief",
                    "OPEN",
                    null,
                    null,
                    "a-different-professional",
                    null,
                    null
                )
            )
        );
        when(patientService.profiles()).thenReturn(List.of(profile("patient-other", "Not", "Mine", "female", LocalDate.of(1990, 1, 1))));

        assertThat(service.directory()).isEmpty();
    }

    @Test
    void aPatientOutsideTheCaseloadCannotBeReadById() {
        // The authorization boundary. Without this the endpoint reads any patient by guessing an id.
        when(patientService.profiles()).thenReturn(List.of(profile("patient-other", "Not", "Mine", "female", LocalDate.of(1990, 1, 1))));

        assertThat(service.record("patient-other")).isEmpty();
    }

    @Test
    void aPatientInTheCaseloadCanBeRead() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("patient-mine")));
        when(patientService.profiles("patient-mine")).thenReturn(
            List.of(profile("patient-mine", "Ama", "Mensah", "female", LocalDate.of(1990, 1, 1)))
        );

        assertThat(service.record("patient-mine")).isPresent().get().extracting("patientName").isEqualTo("Ama Mensah");
    }

    @Test
    void childhoodIsComputedFromTheBirthDateNotStored() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("kid"), task("grown")));
        when(patientService.profiles()).thenReturn(
            List.of(
                profile("kid", "Young", "Patient", "male", LocalDate.now().minusYears(7)),
                profile("grown", "Older", "Patient", "male", LocalDate.now().minusYears(40))
            )
        );

        assertThat(service.directory()).filteredOn("isChild", true).extracting("id").containsExactly("kid");
    }

    @Test
    void anUnrecognisedSexBecomesUnspecifiedRatherThanLeaking() {
        // PatientSexDto is a closed set in the frontend; anything else would render as a raw value.
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("p1")));
        when(patientService.profiles()).thenReturn(List.of(profile("p1", "Odd", "Value", "not-a-sex", LocalDate.of(1990, 1, 1))));

        assertThat(service.directory()).singleElement().extracting("sex").isEqualTo("unspecified");
    }

    @Test
    void anAccountWithNoProfileHasAnEmptyDirectoryRatherThanAnError() {
        // A registered account before onboarding completes. It genuinely has no patients.
        when(profileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(service.directory()).isEmpty();
        assertThat(service.summary().patients()).isZero();
    }

    @Test
    void aPatientStackWithNoProfilesEmptiesTheDirectoryRatherThanFailing() {
        // A collection that is genuinely empty, which is a legitimate answer and stays one: the
        // clinician has a task against a patient the sibling holds no profile for.
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("patient-mine")));
        when(patientService.profiles()).thenReturn(List.of());

        assertThat(service.directory()).isEmpty();
    }

    // --- An outage is not a caseload decision (backlog.md item 24) ----------------------------

    private static PatientServiceUnavailableException outage() {
        return PatientServiceUnavailableException.read(
            "/api/clinical-cases",
            PatientServiceUnavailableException.Fault.TRANSPORT,
            "connection refused"
        );
    }

    /**
     * The defect item 24 is named for: a clinician standing next to their own patient was told the
     * patient was not in their caseload, because the caseload could not be read.
     *
     * <p>{@code record} returns {@code Optional.empty()} for "no such patient, or not yours", and
     * {@code PatientResource} turns that into a 404. A read that never happened must not reach that
     * answer — the union it is computed from is half patientservice's, and half of an entitlement set
     * is an entitlement set that says no to real patients.
     */
    @Test
    void anOutageIsNOTanEmptyRecord() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("patient-mine")));
        when(patientService.clinicalCases("patient-mine")).thenThrow(outage());

        assertThatThrownBy(() -> service.record("patient-mine")).isInstanceOf(PatientServiceUnavailableException.class);
    }

    /**
     * A task-entitled patient is still a 503 during an outage, not a 200 with an empty record.
     *
     * <p>The scoped read of backlog item 23 made this reachable in a way it was not before: the
     * entitlement question for one patient could now be answered from the task half alone, so the case
     * read could have been skipped when a task already granted. It is not, deliberately —
     * {@code entitledCases} reads before it consults tasks. Serving a record with no cases, no
     * activity and no medications because the sibling is down is item 24's conflation wearing a
     * different hat, and this is what pins the ordering.
     */
    @Test
    void aTASKentitledPatientIsStillA503DuringAnOutage() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("patient-mine")));
        when(patientService.clinicalCases("patient-mine")).thenThrow(outage());
        when(patientService.profiles("patient-mine")).thenReturn(
            List.of(profile("patient-mine", "Ama", "Mensah", "female", LocalDate.of(1990, 1, 1)))
        );

        assertThatThrownBy(() -> service.record("patient-mine")).isInstanceOf(PatientServiceUnavailableException.class);
    }

    /** The same, one layer down: the profile read fails after the entitlement check has passed. */
    @Test
    void anOutageReadingTheProfilesIsNOTanEmptyRecordEither() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("patient-mine")));
        when(patientService.profiles(anyString())).thenThrow(
            PatientServiceUnavailableException.read(
                "/api/profiles",
                PatientServiceUnavailableException.Fault.TRANSPORT,
                "connection refused"
            )
        );

        assertThatThrownBy(() -> service.record("patient-mine")).isInstanceOf(PatientServiceUnavailableException.class);
    }

    /**
     * A genuinely unknown patient is still {@code Optional.empty()} — the 404 half of the pair.
     *
     * <p>Asserted beside the outage cases on purpose: a fix that raised on both would be no better
     * than the empty list that answered both, and would hand any clinician a probe for real patient
     * ids into the bargain.
     */
    @Test
    void aPatientTrulyOutsideTheCaseloadIsStillAnEmptyRecord() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("patient-mine")));
        when(patientService.clinicalCases("patient-someone-else")).thenReturn(List.of());

        assertThat(service.record("patient-someone-else")).isEmpty();
    }

    /**
     * The detail path reads {@code casesIncludingArchived} since backlog item 82, and item 24's rule
     * has to hold on that read too: a collection that could not be read is a 503, never "no such case".
     */
    @Test
    void anOutageIsNOTaPatientNotInCaseloadOnTheCaseDetail() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task(MINE)));
        when(patientService.casesIncludingArchived(MINE)).thenThrow(outage());

        assertThatThrownBy(() -> service.caseDetail(MINE, "c1"))
            .isInstanceOf(PatientServiceUnavailableException.class)
            .isNotInstanceOf(PatientDirectoryService.PatientNotInCaseloadException.class);
    }

    @Test
    void anOutageIsNOTaPatientNotInCaseloadOnAnEdit() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task(MINE)));
        when(patientService.clinicalCases(MINE)).thenThrow(outage());

        assertThatThrownBy(() -> service.updateCase(MINE, "c1", new PatientDirectoryService.CaseUpdate("x", null, null, null)))
            .isInstanceOf(PatientServiceUnavailableException.class)
            .isNotInstanceOf(PatientDirectoryService.PatientNotInCaseloadException.class);
        verify(patientService, org.mockito.Mockito.never()).patchClinicalCase(any(), any());
    }

    @Test
    void anOutageIsNOTanEmptyCaseQueueOrAZeroSummary() {
        // The two aggregate screens. An empty queue reads as "you have no cases today" and a zero
        // summary as "you have no patients" — both plausible, both wrong, and neither recoverable by
        // the clinician looking at it.
        when(patientService.clinicalCases()).thenThrow(outage());
        when(patientService.profiles()).thenThrow(
            PatientServiceUnavailableException.read(
                "/api/profiles",
                PatientServiceUnavailableException.Fault.TRANSPORT,
                "connection refused"
            )
        );

        assertThatThrownBy(() -> service.myCases(PageRequest.of(0, 20), null)).isInstanceOf(PatientServiceUnavailableException.class);
        assertThatThrownBy(() -> service.summary()).isInstanceOf(PatientServiceUnavailableException.class);
    }

    @Test
    void theSummaryCountsOnlyWhatThisServiceOwns() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("f1"), task("m1"), task("kid")));
        when(patientService.profiles()).thenReturn(
            List.of(
                profile("f1", "F", "One", "female", LocalDate.of(1990, 1, 1)),
                profile("m1", "M", "One", "male", LocalDate.of(1985, 1, 1)),
                profile("kid", "K", "One", "female", LocalDate.now().minusYears(5))
            )
        );

        var summary = service.summary();
        assertThat(summary.patients()).isEqualTo(3);
        assertThat(summary.female()).isEqualTo(2);
        assertThat(summary.male()).isEqualTo(1);
        assertThat(summary.kids()).isEqualTo(1);
    }

    // --- A refusal is not an outage, and not an empty panel (backlog.md item 107) -------------

    /**
     * hc-patient answering <b>403</b>, which is what a pharmacist, a chemist or a technician gets from
     * {@code /api/activity-logs} and a technician also gets from {@code /api/clinical-cases}.
     *
     * <p>Deliberately built from the {@code Fault} and not from a status, because that is the only
     * thing the service under test is allowed to key on: classifying the status is
     * {@code PatientServiceClient}'s job and is held by {@code PatientServiceFaultTest}, so a test here
     * that constructed a 403 would be asserting two decisions through one another.
     */
    private static PatientServiceUnavailableException refused(String path) {
        return PatientServiceUnavailableException.read(path, PatientServiceUnavailableException.Fault.UPSTREAM_FORBIDDEN, "Forbidden");
    }

    private static ActivityLog loggedFor(String id, String patientId) {
        return new ActivityLog(
            id,
            patientId,
            null,
            Instant.parse("2026-09-10T08:00:00Z"),
            "seen",
            "detail",
            "OBSERVATION",
            "CLINICIAN",
            PROFESSIONAL_ID,
            LocalDate.of(2026, 9, 10)
        );
    }

    /** One patient reached only by a task, one reached only by a case, and an activity for each. */
    private void onePatientByTaskAndOneByCase() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("p-task")));
        when(patientService.clinicalCases()).thenReturn(List.of(aCase("c-1", "p-case", "OPEN", PROFESSIONAL_ID)));
        when(patientService.profiles()).thenReturn(
            List.of(
                profile("p-task", "Task", "Patient", "female", LocalDate.of(1990, 1, 1)),
                profile("p-case", "Case", "Patient", "male", LocalDate.of(1980, 1, 1))
            )
        );
        when(patientService.activityLogs()).thenReturn(List.of(loggedFor("al-task", "p-task"), loggedFor("al-case", "p-case")));
    }

    /**
     * <b>The positive control.</b> Five of the eight disciplines may read every part, and without this
     * case a change that emptied the directory for everybody would look exactly like a pass on the two
     * cases below — both of which assert that <em>something</em> survived a refusal, and both of which
     * an always-empty directory would satisfy for the rows it no longer had to produce.
     */
    @Test
    void aDisciplineEntitledToEveryPartGetsTheWholeDirectory() {
        onePatientByTaskAndOneByCase();

        PatientDirectoryService.Directory directory = service.directory(PageRequest.of(0, 20), DirectoryFilter.NONE);

        assertThat(directory.page().getContent()).extracting(PatientListItem::id).containsExactlyInAnyOrder("p-task", "p-case");
        assertThat(directory.page().getContent()).extracting(PatientListItem::lastActivityAt).doesNotContainNull();
        assertThat(directory.restrictions()).isEmpty();
    }

    /**
     * A pharmacist or a chemist: the activity log is refused, every patient is still theirs.
     *
     * <p>The restriction is asserted <em>beside</em> the rows, because a directory that came back
     * without saying what was missing is the conflation this item exists to close — "no activity" and
     * "not yours to see" are not the same sentence, and a null {@code lastActivityAt} alone says the
     * first one.
     */
    @Test
    void aDisciplineREFUSEDoneComposedPartStillGetsTheDirectory() {
        onePatientByTaskAndOneByCase();
        when(patientService.activityLogs()).thenThrow(refused("/api/activity-logs"));

        PatientDirectoryService.Directory directory = service.directory(PageRequest.of(0, 20), DirectoryFilter.NONE);

        assertThat(directory.page().getContent()).extracting(PatientListItem::id).containsExactlyInAnyOrder("p-task", "p-case");
        assertThat(directory.restrictions()).containsExactly(PatientDirectoryService.RestrictedPart.LAST_ACTIVITY);
        assertThat(directory.page().getContent()).extracting(PatientListItem::lastActivityAt).containsOnlyNulls();
    }

    /**
     * A technician: the case collection itself is refused, so the caseload union loses a whole half.
     *
     * <p>Two assertions and the second matters as much as the first. The task half is served — that is
     * the directory the item is about. The case-only patient is <b>absent</b>, because degrading may
     * only ever make this list smaller: a fallback that invented an entitlement would be a far worse
     * defect than the 503 it replaced.
     */
    @Test
    void aREFUSEDcaseCollectionLeavesTheTASKhalfOfTheDirectory() {
        onePatientByTaskAndOneByCase();
        when(patientService.clinicalCases()).thenThrow(refused("/api/clinical-cases"));

        PatientDirectoryService.Directory directory = service.directory(PageRequest.of(0, 20), DirectoryFilter.NONE);

        assertThat(directory.page().getContent()).extracting(PatientListItem::id).containsExactly("p-task");
        assertThat(directory.restrictions()).containsExactly(PatientDirectoryService.RestrictedPart.CASE_ASSIGNMENTS);
    }

    /**
     * <b>The thing most at risk from this change.</b> A sibling that is genuinely broken must still be
     * a 503 — the class javadoc's whole argument is that a clinician told "this patient is not yours"
     * during an outage is worse off than one told to wait, and tolerating a refusal must not become
     * tolerating that.
     *
     * <p>Both reads are exercised separately rather than together: one tolerant catch left too wide
     * would be caught by either, but one of the two left wide would not.
     */
    @Test
    void anOUTAGEonTheActivityLogIsStillA503_notADegradedDirectory() {
        onePatientByTaskAndOneByCase();
        when(patientService.activityLogs()).thenThrow(outage());

        assertThatThrownBy(() -> service.directory(PageRequest.of(0, 20), DirectoryFilter.NONE)).isInstanceOf(
            PatientServiceUnavailableException.class
        );
    }

    @Test
    void anOUTAGEonTheCaseCollectionIsStillA503_notATaskOnlyDirectory() {
        onePatientByTaskAndOneByCase();
        when(patientService.clinicalCases()).thenThrow(outage());

        assertThatThrownBy(() -> service.directory(PageRequest.of(0, 20), DirectoryFilter.NONE)).isInstanceOf(
            PatientServiceUnavailableException.class
        );
    }

    /**
     * The profile read is not in the tolerant set, and the rule is stated here rather than only in a
     * javadoc: it supplies every row's substance, so a caller refused it has no directory to be handed
     * a degraded version of. An empty list with a note attached would be the worse answer.
     */
    @Test
    void aREFUSEDprofileReadIsStillA503_becauseThereIsNoDirectoryWithoutIt() {
        onePatientByTaskAndOneByCase();
        when(patientService.profiles()).thenThrow(refused("/api/profiles"));

        assertThatThrownBy(() -> service.directory(PageRequest.of(0, 20), DirectoryFilter.NONE)).isInstanceOf(
            PatientServiceUnavailableException.class
        );
    }

    /**
     * The summary does <b>not</b> degrade, and that is a decision rather than an omission.
     *
     * <p>Every field of {@code DashboardSummary} is a count and it has nowhere to say that one is
     * partial, so tolerating a refusal there would turn "your caseload could not be counted" into a
     * confident wrong number — the same conflation, in the one shape where nothing on screen could
     * reveal it. Asserted so that a later change cannot route it through the tolerant path by accident.
     */
    @Test
    void theSummaryDoesNOTdegradeOnARefusal() {
        onePatientByTaskAndOneByCase();
        when(patientService.activityLogs()).thenThrow(refused("/api/activity-logs"));

        assertThatThrownBy(() -> service.summary()).isInstanceOf(PatientServiceUnavailableException.class);
    }

    // --- Paging, filtering and sorting (web-mobile-port.md § Phase 1.1) -----------------------

    /** Three patients, deterministic names and demographics, for the paging tests below. */
    private void threePatients() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("p-a"), task("p-b"), task("p-c")));
        when(patientService.profiles()).thenReturn(
            List.of(
                profile("p-a", "Ama", "Mensah", "female", LocalDate.of(1990, 1, 1)),
                profile("p-b", "Kwesi", "Boateng", "male", LocalDate.of(1985, 1, 1)),
                profile("p-c", "Akosua", "Owusu", "female", LocalDate.now().minusYears(7))
            )
        );
    }

    @Test
    void aPageCarriesTheMATCHcountRatherThanTheRowCount() {
        // The bug this replaces: X-Total-Count was list.size(), so it agreed with the body by
        // construction and could never tell a client there was another page.
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 2), DirectoryFilter.NONE).page();

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void thePastTheEndPageIsEmptyRatherThanOutOfBounds() {
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(9, 2), DirectoryFilter.NONE).page();

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void theTotalIsTHEFILTEREDtotal_notTheWholeCaseload() {
        // Otherwise the client pages through a phantom: 3 total, 1 row, no second page to fetch.
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 20), new DirectoryFilter(null, "male", null)).page();

        assertThat(page.getContent()).extracting(PatientListItem::id).containsExactly("p-b");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void theQueryMatchesNameOrId_becauseAWristbandCarriesTheId() {
        threePatients();

        assertThat(service.directory(PageRequest.of(0, 20), new DirectoryFilter("mensah", null, null)).page().getContent())
            .extracting(PatientListItem::id)
            .containsExactly("p-a");
        assertThat(service.directory(PageRequest.of(0, 20), new DirectoryFilter("P-C", null, null)).page().getContent())
            .extracting(PatientListItem::id)
            .containsExactly("p-c");
    }

    @Test
    void childrenOnlyUsesTheAgeComputedPerRead() {
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 20), new DirectoryFilter(null, null, true)).page();

        assertThat(page.getContent()).extracting(PatientListItem::id).containsExactly("p-c");
    }

    @Test
    void filtersCOMBINE_theyDoNotWiden() {
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 20), new DirectoryFilter("akosua", "male", null)).page();

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void aWhitelistedSortIsApplied() {
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 20, Sort.by("patientName")), DirectoryFilter.NONE).page();

        assertThat(page.getContent()).extracting(PatientListItem::patientName).isSorted();
    }

    @Test
    void anUnSORTABLEpropertyIsREJECTED_notSilentlyIgnored() {
        // The list is assembled in memory, so a sort is a comparator lookup an arbitrary sort= would
        // otherwise reach. Ignoring it instead would look like a backend that lost the clinician's
        // ordering — which nobody reports as a bug.
        threePatients();

        assertThatThrownBy(() -> service.directory(PageRequest.of(0, 20, Sort.by("dropTable")), DirectoryFilter.NONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dropTable")
            .hasMessageContaining("patientName");
    }

    @Test
    void anUnpagedRequestStillAnswersWithEverything() {
        // summary() and any internal caller must not silently receive page 0 of 20.
        threePatients();

        Page<PatientListItem> page = service.directory(Pageable.unpaged(), DirectoryFilter.NONE).page();

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void aNullFilterMeansNoFilter() {
        threePatients();

        assertThat(service.directory(PageRequest.of(0, 20), null).page().getTotalElements()).isEqualTo(3);
    }

    // --- Writes (web-mobile-port.md § Phase 1.3) ----------------------------------------------

    private static final String MINE = "p-mine";

    /**
     * One patient in the caller's caseload, so a write has somewhere legitimate to land.
     *
     * <p>The profile is stubbed on both forms of the read: the directory takes the estate-wide one and
     * a patient record takes the scoped one (backlog item 23), and tests here use both.
     */
    private void onePatientOfMine() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task(MINE)));
        when(patientService.profiles()).thenReturn(List.of(profile(MINE, "Ama", "Mensah", "female", LocalDate.of(1990, 1, 1))));
        when(patientService.profiles(MINE)).thenReturn(List.of(profile(MINE, "Ama", "Mensah", "female", LocalDate.of(1990, 1, 1))));
    }

    private static ActivityLog createdLog(String id, String clientSummary) {
        return new ActivityLog(
            id,
            MINE,
            null,
            Instant.parse("2026-08-22T09:15:00Z"),
            clientSummary,
            "detail",
            "OBSERVATION",
            "CLINICIAN",
            PROFESSIONAL_ID,
            LocalDate.of(2026, 8, 22)
        );
    }

    @Test
    void anActivityIsFiledAgainstMyOwnPatient() {
        onePatientOfMine();
        when(patientService.createActivityLog(any())).thenReturn(createdLog("al-1", "Wound dressed"));

        var entry = service.appendActivity(MINE, new CreateActivity("Wound dressed", "No exudate", null, null));

        assertThat(entry.id()).isEqualTo("al-1");
        assertThat(entry.label()).isEqualTo("Wound dressed");
    }

    @Test
    void theFRONTENDsFieldNamesAreTranslatedToTheSIBLINGs() {
        // title/description here, summary/detail over there. The translation happens once, in this
        // service, rather than making either side rename.
        onePatientOfMine();
        when(patientService.createActivityLog(any())).thenReturn(createdLog("al-1", "Wound dressed"));

        service.appendActivity(MINE, new CreateActivity("Wound dressed", "No exudate", null, null));

        ArgumentCaptor<java.util.Map<String, Object>> body = ArgumentCaptor.forClass(java.util.Map.class);
        verify(patientService).createActivityLog(body.capture());
        assertThat(body.getValue()).containsEntry("summary", "Wound dressed").containsEntry("detail", "No exudate");
        assertThat(body.getValue()).doesNotContainKeys("title", "description");
    }

    @Test
    void aMissingOccurredAtMeansNOW_ratherThanARefusedNote() {
        onePatientOfMine();
        when(patientService.createActivityLog(any())).thenReturn(createdLog("al-1", "s"));

        service.appendActivity(MINE, new CreateActivity("s", "d", null, null));

        ArgumentCaptor<java.util.Map<String, Object>> body = ArgumentCaptor.forClass(java.util.Map.class);
        verify(patientService).createActivityLog(body.capture());
        assertThat(body.getValue().get("loggedAt")).asString().isNotBlank();
    }

    @Test
    void anUNPARSEABLEoccurredAtIsIgnoredRatherThanRefused() {
        // Refusing a clinical note over a malformed timestamp is the wrong trade; the audit fields
        // are stamped from the token by patientservice either way.
        onePatientOfMine();
        when(patientService.createActivityLog(any())).thenReturn(createdLog("al-1", "s"));

        assertThat(service.appendActivity(MINE, new CreateActivity("s", "d", "not-a-timestamp", null))).isNotNull();
    }

    @Test
    void aPatientOUTSIDEmyCaseloadCannotBeWrittenTo() {
        // The write half of the same boundary record() enforces. 404-shaped, so a clinician cannot
        // discover that a patient id is real by trying to file against it.
        onePatientOfMine();

        assertThatThrownBy(() -> service.appendActivity("p-someone-else", new CreateActivity("s", "d", null, null))).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
        verify(patientService, org.mockito.Mockito.never()).createActivityLog(any());
    }

    @Test
    void aRETRIEDwriteFilesTheNoteONCE() {
        // The reason clientRef exists: a phone that files at the bedside, loses signal before the
        // response lands and retries on reconnect must not leave two notes in the record.
        onePatientOfMine();
        when(patientService.createActivityLog(any())).thenReturn(createdLog("al-1", "Wound dressed"));
        when(patientService.activityLogs(MINE)).thenReturn(List.of(createdLog("al-1", "Wound dressed")));

        var first = service.appendActivity(MINE, new CreateActivity("Wound dressed", "d", null, "ref-1"));
        var replay = service.appendActivity(MINE, new CreateActivity("Wound dressed", "d", null, "ref-1"));

        verify(patientService, org.mockito.Mockito.times(1)).createActivityLog(any());
        assertThat(first.id()).isEqualTo("al-1");
        assertThat(replay.id()).isEqualTo("al-1");
    }

    @Test
    void aClientRefSPENTonADIFFERENTwriteIsRefused() {
        // Two clients generating the same key must not let one read back the other's record.
        onePatientOfMine();
        when(patientService.createActivityLog(any())).thenReturn(createdLog("al-1", "s"));
        service.appendActivity(MINE, new CreateActivity("s", "d", null, "ref-1"));

        assertThatThrownBy(() -> service.appendReport(MINE, new CreateReport("r", "ASSESSMENT", "d", null, "ref-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already been used");
    }

    @Test
    void aWriteWithNOclientRefIsStillAccepted() {
        // Idempotency is opt-in. A browser posting a form has no queue and needs no key.
        onePatientOfMine();
        when(patientService.createActivityLog(any())).thenReturn(createdLog("al-1", "s"));

        service.appendActivity(MINE, new CreateActivity("s", "d", null, null));
        service.appendActivity(MINE, new CreateActivity("s", "d", null, null));

        verify(patientService, org.mockito.Mockito.times(2)).createActivityLog(any());
    }

    @Test
    void aReportTranslatesReportTypeToCategory() {
        onePatientOfMine();
        when(patientService.createReport(any())).thenReturn(
            new Report(
                "rep-1",
                MINE,
                null,
                "assessment.pdf",
                "ASSESSMENT",
                "d",
                "s",
                "http://x",
                PROFESSIONAL_ID,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 20)
            )
        );

        var report = service.appendReport(MINE, new CreateReport("assessment.pdf", "ASSESSMENT", "d", "http://x", null));

        ArgumentCaptor<java.util.Map<String, Object>> body = ArgumentCaptor.forClass(java.util.Map.class);
        verify(patientService).createReport(body.capture());
        assertThat(body.getValue()).containsEntry("category", "ASSESSMENT");
        assertThat(report.reportType()).isEqualTo("ASSESSMENT");
    }

    /** A stand-in for the Mongo repository; only findByClientRef and save are exercised. */
    private static final class InMemoryReceiptRepository
        extends org.mockito.Mockito
        implements net.jojoaddison.repository.PatientWriteReceiptRepository {

        private final java.util.Map<String, net.jojoaddison.domain.PatientWriteReceipt> byRef = new java.util.HashMap<>();

        @Override
        public java.util.Optional<net.jojoaddison.domain.PatientWriteReceipt> findByClientRef(String clientRef) {
            return java.util.Optional.ofNullable(byRef.get(clientRef));
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> S save(S entity) {
            byRef.put(entity.getClientRef(), entity);
            return entity;
        }

        // The rest of MongoRepository is unused here.
        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> java.util.List<S> saveAll(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<net.jojoaddison.domain.PatientWriteReceipt> findById(String s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsById(String s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<net.jojoaddison.domain.PatientWriteReceipt> findAll() {
            return java.util.List.copyOf(byRef.values());
        }

        @Override
        public java.util.List<net.jojoaddison.domain.PatientWriteReceipt> findAllById(Iterable<String> strings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            return byRef.size();
        }

        @Override
        public void deleteById(String s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(net.jojoaddison.domain.PatientWriteReceipt entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllById(Iterable<? extends String> strings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll(Iterable<? extends net.jojoaddison.domain.PatientWriteReceipt> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll() {
            byRef.clear();
        }

        @Override
        public java.util.List<net.jojoaddison.domain.PatientWriteReceipt> findAll(org.springframework.data.domain.Sort sort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.springframework.data.domain.Page<net.jojoaddison.domain.PatientWriteReceipt> findAll(
            org.springframework.data.domain.Pageable pageable
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> java.util.Optional<S> findOne(
            org.springframework.data.domain.Example<S> example
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> java.util.List<S> findAll(
            org.springframework.data.domain.Example<S> example
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> java.util.List<S> findAll(
            org.springframework.data.domain.Example<S> example,
            org.springframework.data.domain.Sort sort
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> org.springframework.data.domain.Page<S> findAll(
            org.springframework.data.domain.Example<S> example,
            org.springframework.data.domain.Pageable pageable
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> long count(org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> boolean exists(org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt, R> R findBy(
            org.springframework.data.domain.Example<S> example,
            java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> S insert(S entity) {
            return save(entity);
        }

        @Override
        public <S extends net.jojoaddison.domain.PatientWriteReceipt> java.util.List<S> insert(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }
    }

    // --- Cases (web-mobile-port.md § Phase 1.4) -----------------------------------------------

    private static PatientServiceDtos.ClinicalCase aCase(String id, String patientId, String status, String assignedTo) {
        return new PatientServiceDtos.ClinicalCase(
            id,
            patientId,
            1,
            "Title",
            Instant.parse("2026-08-20T09:00:00Z"),
            null,
            "brief",
            status,
            "symptoms",
            "diagnosis",
            assignedTo,
            null,
            null
        );
    }

    @Test
    void aQueuedCaseCARRIESitsPatientId() {
        // Without it the queue lists cases a client cannot open, edit or navigate from: editing goes
        // to /api/patients/{patientId}/cases/{caseId}, and the patient is what the entitlement check
        // checks against. Shipping the queue first is what revealed it.
        when(patientService.clinicalCases()).thenReturn(List.of(aCase("c-mine", MINE, "OPEN", PROFESSIONAL_ID)));

        assertThat(service.myCases(PageRequest.of(0, 20), null).getContent())
            .singleElement()
            .satisfies(summary -> assertThat(summary.patientId()).isEqualTo(MINE));
    }

    @Test
    void theCaseQueueIsMINEonly() {
        // The reason this is proxied at all: patientservice's own endpoint has no clinician scope,
        // so a client calling it directly receives every case in the estate.
        when(patientService.clinicalCases()).thenReturn(
            List.of(aCase("c-mine", MINE, "OPEN", PROFESSIONAL_ID), aCase("c-theirs", "p-other", "OPEN", "someone-else"))
        );

        assertThat(service.myCases(PageRequest.of(0, 20), null).getContent()).extracting("id").containsExactly("c-mine");
    }

    @Test
    void anARCHIVEDcaseIsNotInTheWORKINGqueue() {
        var archived = new PatientServiceDtos.ClinicalCase(
            "c-old",
            MINE,
            1,
            "t",
            Instant.parse("2026-08-20T09:00:00Z"),
            null,
            "b",
            "OPEN",
            null,
            null,
            PROFESSIONAL_ID,
            null,
            Instant.parse("2026-08-21T09:00:00Z")
        );
        when(patientService.clinicalCases()).thenReturn(List.of(archived, aCase("c-open", MINE, "OPEN", PROFESSIONAL_ID)));

        assertThat(service.myCases(PageRequest.of(0, 20), null).getContent()).extracting("id").containsExactly("c-open");
    }

    @Test
    void theQueueFiltersByStatusCaseInsensitively() {
        when(patientService.clinicalCases()).thenReturn(
            List.of(aCase("c-open", MINE, "OPEN", PROFESSIONAL_ID), aCase("c-closed", MINE, "CLOSED", PROFESSIONAL_ID))
        );

        assertThat(service.myCases(PageRequest.of(0, 20), "open").getContent()).extracting("id").containsExactly("c-open");
    }

    @Test
    void theQueueTotalIsTheFilteredTotal() {
        when(patientService.clinicalCases()).thenReturn(
            List.of(
                aCase("c1", MINE, "OPEN", PROFESSIONAL_ID),
                aCase("c2", MINE, "OPEN", PROFESSIONAL_ID),
                aCase("c3", MINE, "CLOSED", PROFESSIONAL_ID)
            )
        );

        var page = service.myCases(PageRequest.of(0, 1), "OPEN");

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void aCaseOnAPatientOUTSIDEmyCaseloadCannotBeEdited() {
        onePatientOfMine();
        // The case is real and readable; it is simply nobody's business here. Stubbed on the scoped
        // read so the refusal comes from the entitlement rule rather than from an empty stub.
        when(patientService.clinicalCases("p-other")).thenReturn(List.of(aCase("c-theirs", "p-other", "OPEN", "someone-else")));

        assertThatThrownBy(
            () -> service.updateCase("p-other", "c-theirs", new PatientDirectoryService.CaseUpdate("x", null, null, null))
        ).isInstanceOf(PatientDirectoryService.PatientNotInCaseloadException.class);
        verify(patientService, org.mockito.Mockito.never()).patchClinicalCase(any(), any());
    }

    @Test
    void onlyTheFOUReditableFieldsAreForwarded() {
        // A whole-document PATCH would let a caller move a case to another patient or reassign it.
        onePatientOfMine();
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));
        when(patientService.patchClinicalCase(any(), any())).thenReturn(aCase("c1", MINE, "CLOSED", PROFESSIONAL_ID));

        service.updateCase(MINE, "c1", new PatientDirectoryService.CaseUpdate("new symptoms", null, null, "CLOSED"));

        ArgumentCaptor<java.util.Map<String, Object>> body = ArgumentCaptor.forClass(java.util.Map.class);
        verify(patientService).patchClinicalCase(org.mockito.ArgumentMatchers.eq("c1"), body.capture());
        assertThat(body.getValue()).containsEntry("symptoms", "new symptoms").containsEntry("status", "CLOSED");
        assertThat(body.getValue()).doesNotContainKeys("patientId", "assignedProfessionalId", "assignedRosterId", "archivedAt");
    }

    @Test
    void theBodyCarriesTheIdPatientserviceInsistsOn() {
        // Its PATCH rejects a body whose id does not match the path — a JHipster convention.
        onePatientOfMine();
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));
        when(patientService.patchClinicalCase(any(), any())).thenReturn(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID));

        service.updateCase(MINE, "c1", new PatientDirectoryService.CaseUpdate("s", null, null, null));

        ArgumentCaptor<java.util.Map<String, Object>> body = ArgumentCaptor.forClass(java.util.Map.class);
        verify(patientService).patchClinicalCase(any(), body.capture());
        assertThat(body.getValue()).containsEntry("id", "c1");
    }

    @Test
    void aNullFieldIsOMITTEDratherThanSentAsNull() {
        // A merge-patch null means "clear this". Sending one for every untouched field would wipe a
        // diagnosis because the clinician edited the symptoms.
        onePatientOfMine();
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));
        when(patientService.patchClinicalCase(any(), any())).thenReturn(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID));

        service.updateCase(MINE, "c1", new PatientDirectoryService.CaseUpdate("s", null, null, null));

        ArgumentCaptor<java.util.Map<String, Object>> body = ArgumentCaptor.forClass(java.util.Map.class);
        verify(patientService).patchClinicalCase(any(), body.capture());
        assertThat(body.getValue()).doesNotContainKey("diagnosis");
    }

    @Test
    void aPatientsCasesAreEntitlementChecked() {
        onePatientOfMine();
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));

        assertThat(service.casesFor(MINE, PageRequest.of(0, 20)).getContent()).extracting("id").containsExactly("c1");
        assertThatThrownBy(() -> service.casesFor("p-other", PageRequest.of(0, 20))).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
    }

    // --- What is asked of the sibling, not what comes back (backlog.md item 23) ----------------

    /**
     * A patient record asks the sibling for <b>that patient's</b> rows, on all four collections.
     *
     * <p><b>Asserted on the call, because the result cannot tell the two apart.</b> Reading ~1260
     * estate-wide cases and keeping one patient's produces exactly the list this method produces now —
     * that is why the cost survived item 22's review, and it is the whole reason item 23 is a separate
     * entry. Any assertion on the returned record passes against the expensive version.
     *
     * <p>The {@code never()} half is not decoration either: a "fix" that called the scoped read and
     * then fell back to the estate-wide one, or that left one of the four behind, would satisfy the
     * first half of this test and change nothing about what crosses the network.
     */
    @Test
    void aPatientRecordReadsONLYthatPatientsRowsFromTheSibling() {
        onePatientOfMine();
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));

        assertThat(service.record(MINE)).isPresent();

        // atLeastOnce, not once: how many times the cases are read is the next test's subject, and a
        // guard that fails for two separate reasons cannot tell you which one broke.
        verify(patientService, org.mockito.Mockito.atLeastOnce()).clinicalCases(MINE);
        verify(patientService).profiles(MINE);
        verify(patientService).activityLogs(MINE);
        verify(patientService).medications(MINE);
        verify(patientService).reports(MINE);
        verify(patientService, org.mockito.Mockito.never()).clinicalCases();
        verify(patientService, org.mockito.Mockito.never()).profiles();
        verify(patientService, org.mockito.Mockito.never()).activityLogs();
        verify(patientService, org.mockito.Mockito.never()).medications();
        verify(patientService, org.mockito.Mockito.never()).reports();
    }

    /**
     * And it reads the case collection <b>once</b>, not twice.
     *
     * <p>{@code record()} read {@code /api/clinical-cases} twice in one method: once through the
     * entitlement check and once for the case list, on the same collection, in the same request. That
     * was two HTTP calls before item 22 made the reads complete and roughly fourteen after, at seven
     * requests a read. The rows the check already holds are now the rows the list is built from.
     */
    @Test
    void aPatientRecordReadsTheCaseCollectionONCE() {
        onePatientOfMine();
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));

        assertThat(service.record(MINE)).isPresent().get().extracting("cases").asInstanceOf(LIST).hasSize(1);

        verify(patientService, org.mockito.Mockito.times(1)).clinicalCases(MINE);
    }

    /**
     * The same, for the three surfaces that check entitlement and then answer from the same rows.
     *
     * <p>Each of these used to read the case collection twice for one request — the check, then the
     * answer — which is the identical defect the record had, in three smaller places.
     */
    @Test
    void aCaseListAnEditAndADetailEachReadTheCasesONCE() {
        onePatientOfMine();
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));
        when(patientService.casesIncludingArchived(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));
        when(patientService.patchClinicalCase(any(), any())).thenReturn(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID));

        service.casesFor(MINE, PageRequest.of(0, 20));
        verify(patientService, org.mockito.Mockito.times(1)).clinicalCases(MINE);

        // The detail reads the archived-inclusive form (item 82) and still reads it once: it checks the
        // entitlement and answers from the same rows, which is what item 23 established here.
        org.mockito.Mockito.clearInvocations(patientService);
        service.caseDetail(MINE, "c1");
        verify(patientService, org.mockito.Mockito.times(1)).casesIncludingArchived(MINE);
        verify(patientService, org.mockito.Mockito.never()).clinicalCases(MINE);

        org.mockito.Mockito.clearInvocations(patientService);
        service.updateCase(MINE, "c1", new PatientDirectoryService.CaseUpdate("s", null, null, null));
        verify(patientService, org.mockito.Mockito.times(1)).clinicalCases(MINE);

        verify(patientService, org.mockito.Mockito.never()).clinicalCases();
    }

    /**
     * A write's entitlement check is scoped too — and it is the only sibling read it makes.
     *
     * <p>{@code appendActivity} does not need the cases it reads; it needs to know the patient is the
     * caller's, and that question is now asked about one patient rather than the estate. The rows come
     * back because the check has them anyway.
     */
    @Test
    void aWriteChecksEntitlementWithAScopedReadRatherThanTheWholeEstate() {
        onePatientOfMine();
        when(patientService.createActivityLog(any())).thenReturn(createdLog("al-1", "Wound dressed"));

        service.appendActivity(MINE, new CreateActivity("Wound dressed", "No exudate", null, null));

        verify(patientService).clinicalCases(MINE);
        verify(patientService, org.mockito.Mockito.never()).clinicalCases();
    }

    /**
     * A sibling that ignored the filter could not widen anyone's caseload.
     *
     * <p><b>The scoped read is a cost decision; the entitlement rule may not rest on it.</b> Backlog
     * item 23 moved the narrowing of these rows to the far side of a network hop, and the rows decide
     * whether a clinician may read and write a patient — so if that hop ever answered with the estate
     * (a sibling ignoring {@code patientId}, which is a failure mode this client already guards for in
     * its paging), every clinician holding one case anywhere would be entitled to every patient in the
     * platform. {@code entitledCases} narrows again locally, and this is what says so.
     *
     * <p>Staged by stubbing the scoped call with a row belonging to somebody else, which is precisely
     * what an ignored parameter looks like from this side.
     *
     * <p><b>The third arm is {@code caseDetail}, added after review.</b> Backlog item 82 moved that
     * path onto its own read and its own copy of the rule, and its in-method {@code patientId} filter
     * carried a comment claiming it was load-bearing "for the same reason it is there" — while this
     * test, the one holding that reason, covered only the two calls above. Deleting the filter from
     * {@code caseDetail} reddened nothing. Without it, a sibling that ignored {@code patientId} would
     * have {@code assignedToMeLive} computed over the <em>estate's</em> cases, and any clinician
     * holding one live case anywhere could read any case by naming any patient id.
     */
    @Test
    void aSIBLINGthatIGNOREDtheFilterCouldNotWidenTheCaseload() {
        when(patientService.clinicalCases(anyString())).thenReturn(List.of(aCase("c-mine", MINE, "OPEN", PROFESSIONAL_ID)));
        when(patientService.casesIncludingArchived(anyString())).thenReturn(List.of(aCase("c-mine", MINE, "OPEN", PROFESSIONAL_ID)));

        assertThatThrownBy(() -> service.casesFor("p-not-mine", PageRequest.of(0, 20))).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
        assertThatThrownBy(() -> service.appendActivity("p-not-mine", new CreateActivity("s", "d", null, null))).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
        // The case really is readable — under its own patient. What must not happen is it answering
        // for a patient the caller named and has no tie to.
        assertThat(service.caseDetail(MINE, "c-mine").id()).isEqualTo("c-mine");
        assertThatThrownBy(() -> service.caseDetail("p-not-mine", "c-mine")).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
        verify(patientService, org.mockito.Mockito.never()).createActivityLog(any());
    }

    /**
     * The reads that must stay estate-wide, asserted so that "scope everything" is not a fix.
     *
     * <p>The directory and the case queue ask which patients and which cases are <em>this
     * clinician's</em>, and the sibling has no filter for that — only {@code patientId}, which is the
     * answer rather than the question. Scoping them would mean one request per patient, or a request
     * scoped to a null id, and either would be worse than what item 22 left behind. This is the
     * cross-stack half of item 23 and it is deliberately not attempted here.
     */
    @Test
    void theCASELOADunionStillReadsTheEstateWideCollections() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task(MINE)));
        when(patientService.profiles()).thenReturn(List.of(profile(MINE, "Ama", "Mensah", "female", LocalDate.of(1990, 1, 1))));

        service.directory();
        service.myCases(PageRequest.of(0, 20), null);

        verify(patientService, org.mockito.Mockito.atLeastOnce()).clinicalCases();
        verify(patientService).profiles();
        verify(patientService, org.mockito.Mockito.never()).profiles(anyString());
    }

    // --- An archived case is readable, and only by the people it always was (backlog.md item 82) ---

    /** The archived twin of {@link #aCase}: same shape, retired at a stated instant. */
    private static PatientServiceDtos.ClinicalCase anArchivedCase(String id, String patientId, String assignedTo) {
        return new PatientServiceDtos.ClinicalCase(
            id,
            patientId,
            1,
            "Title",
            Instant.parse("2026-08-20T09:00:00Z"),
            null,
            "brief",
            "CLOSED",
            "symptoms",
            "diagnosis",
            assignedTo,
            null,
            Instant.parse("2026-08-21T09:00:00Z")
        );
    }

    /**
     * The promise this method has made since it was written: a case retired while a clinician had it
     * open renders rather than 404ing.
     *
     * <p>It was never kept — the sibling's {@code includeArchived} defaults to false, so the row never
     * arrived and {@code findFirst} missed. Measured before the fix with the caller entitled by a live
     * case, which is what makes this the {@code orElseThrow} half rather than the caseload half.
     */
    @Test
    void anARCHIVEDcaseISreadableOnTheDetail() {
        when(patientService.casesIncludingArchived(MINE)).thenReturn(
            List.of(aCase("c-live", MINE, "OPEN", PROFESSIONAL_ID), anArchivedCase("c-old", MINE, PROFESSIONAL_ID))
        );

        var detail = service.caseDetail(MINE, "c-old");

        assertThat(detail.id()).isEqualTo("c-old");
        assertThat(detail.diagnosis()).isEqualTo("diagnosis");
        // And it says it is archived. Without this a retired diagnosis renders as current clinical
        // prose, which is a worse answer than the 404 this replaces.
        assertThat(detail.archivedAt()).isEqualTo("2026-08-21T09:00:00Z");
    }

    /** A live case is unchanged, and says so by carrying no {@code archivedAt}. */
    @Test
    void aLIVEcaseStillReadsAsLive() {
        when(patientService.casesIncludingArchived(MINE)).thenReturn(List.of(aCase("c-live", MINE, "OPEN", PROFESSIONAL_ID)));

        assertThat(service.caseDetail(MINE, "c-live").archivedAt()).isNull();
    }

    /**
     * The second half of the promise, and the one the ordinary caseload rule cannot keep.
     *
     * <p>A clinician whose only case with this patient is the one they archived has no live row and no
     * task, so the <em>caseload</em> check refuses before the case is ever looked for — a different
     * throw site from the case above, the same 404, and the reason item 82's one-line diagnosis was
     * right about only one of the two. They may read back their own work: the case names them.
     */
    @Test
    void theClinicianWhoseONLYtieIsTheArchivedCaseCanStillReadItBack() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of());
        when(patientService.casesIncludingArchived(MINE)).thenReturn(List.of(anArchivedCase("c-old", MINE, PROFESSIONAL_ID)));

        assertThat(service.caseDetail(MINE, "c-old").id()).isEqualTo("c-old");
    }

    /**
     * <b>The one that matters more.</b> A fix that simply asked for archived rows and kept the old
     * {@code findFirst} would pass both cases above and hand every archived case to anybody who could
     * name a patient and a case id.
     *
     * <p>Here the sibling returns the archived case — the read succeeded, the row exists — and the
     * caller is assigned to nothing of this patient's and has no task for them. Still 404.
     */
    @Test
    void anARCHIVEDcaseIsSTILLrefusedToACallerOUTSIDEtheCaseload() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of());
        when(patientService.casesIncludingArchived("p-other")).thenReturn(List.of(anArchivedCase("c-theirs", "p-other", "someone-else")));

        assertThatThrownBy(() -> service.caseDetail("p-other", "c-theirs")).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
    }

    /**
     * <b>G1: entitled by a live case of my own, reading this patient's OTHER archived case.</b>
     *
     * <p>The case belongs to a different clinician, so {@code thisCaseIsMine} is false and only the
     * {@code assignedToMeLive} arm can serve this — which is the point. This grant is not incidental:
     * patientservice gates {@code /archive} on doctor alone, so the clinician who retires a case is
     * frequently not its assignee, and without this arm the motivating scenario 404s for the very
     * person who archived it.
     *
     * <p><b>Added after review.</b> {@code caseDetail} no longer routes through
     * {@code requireEntitlement}, so its copy of the caseload rule is exercised only from here.
     * Deleting <em>both</em> caseload arms — leaving {@code return thisCaseIsMine} — left the whole
     * suite green at 57/57 before this test and the one below existed.
     */
    @Test
    void aCallerEntitledByTheirOwnLIVEcaseReadsThePatientsOTHERarchivedCase() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of());
        when(patientService.casesIncludingArchived(MINE)).thenReturn(
            List.of(aCase("c-mine-live", MINE, "OPEN", PROFESSIONAL_ID), anArchivedCase("c-theirs-old", MINE, "another-doctor"))
        );

        assertThat(service.caseDetail(MINE, "c-theirs-old").id()).isEqualTo("c-theirs-old");
    }

    /**
     * <b>G2: entitled by a TASK alone — no case of mine at all, live or archived.</b>
     *
     * <p>The caseload union has two halves and a clinician can be scheduled against a patient who has
     * no case assigned to them. The live case here belongs to someone else, so neither
     * {@code assignedToMeLive} nor {@code thisCaseIsMine} can serve it: only {@code scheduledWith} can.
     *
     * <p>Paired with the case above so that the two caseload arms are covered <em>separately</em> —
     * one test covering both would go green again the moment somebody deleted the other arm, which is
     * precisely the silent drift this pair exists to catch.
     */
    @Test
    void aCallerEntitledByATASKaloneReadsThePatientsCase() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task(MINE)));
        when(patientService.casesIncludingArchived(MINE)).thenReturn(List.of(aCase("c-theirs-live", MINE, "OPEN", "another-doctor")));

        assertThat(service.caseDetail(MINE, "c-theirs-live").id()).isEqualTo("c-theirs-live");
    }

    /**
     * And the widening that was deliberately not taken.
     *
     * <p>Reading back one's own archived case must not amount to continuing access to the patient.
     * With the archived case as the only tie: their <em>live</em> case, assigned to whoever has them
     * now, is refused; the patient's record does not open; their case list is refused; and the archived
     * case itself cannot be edited. Only the one read the promise is about succeeds.
     */
    @Test
    void readingBackMYarchivedCaseIsNOTcontinuingAccessToThePatient() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of());
        when(patientService.casesIncludingArchived(MINE)).thenReturn(
            List.of(anArchivedCase("c-mine-old", MINE, PROFESSIONAL_ID), aCase("c-theirs-now", MINE, "OPEN", "someone-else"))
        );
        // What the ordinary reads see: the sibling's default, which excludes the archived row.
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c-theirs-now", MINE, "OPEN", "someone-else")));

        assertThat(service.caseDetail(MINE, "c-mine-old").id()).isEqualTo("c-mine-old");

        assertThatThrownBy(() -> service.caseDetail(MINE, "c-theirs-now")).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
        assertThat(service.record(MINE)).isEmpty();
        assertThatThrownBy(() -> service.casesFor(MINE, PageRequest.of(0, 20))).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
        assertThatThrownBy(
            () -> service.updateCase(MINE, "c-mine-old", new PatientDirectoryService.CaseUpdate("x", null, null, null))
        ).isInstanceOf(PatientDirectoryService.PatientNotInCaseloadException.class);
        verify(patientService, org.mockito.Mockito.never()).patchClinicalCase(any(), any());
    }

    /**
     * The lists are untouched, which is the other half of the decision.
     *
     * <p>Retiring a case is exactly the act of taking it out of the working queue, so nothing that
     * answers with a <em>set</em> of cases may start asking for archived rows — a "fix" that swapped
     * the read everywhere would resurrect retired cases on four screens at once.
     */
    @Test
    void nothingBUTtheDetailAsksTheSiblingForArchivedRows() {
        onePatientOfMine();
        when(patientService.clinicalCases(MINE)).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));

        service.directory();
        service.myCases(PageRequest.of(0, 20), null);
        service.casesFor(MINE, PageRequest.of(0, 20));
        service.record(MINE);

        verify(patientService, org.mockito.Mockito.never()).casesIncludingArchived(anyString());
    }
}
