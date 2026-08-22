package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import net.jojoaddison.service.PatientDirectoryService.DirectoryFilter;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
    private static final String PROFESSIONAL_ID = "professional-1";

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private PatientServiceClient patientService;

    private PatientDirectoryService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(LOGIN, "token"));
        Profile mine = new Profile();
        mine.setId(PROFESSIONAL_ID);
        mine.setAccountId(LOGIN);
        when(profileRepository.findByAccountId(LOGIN)).thenReturn(Optional.of(mine));
        when(taskRepository.findByAttendantId(anyString())).thenReturn(List.of());
        when(patientService.clinicalCases()).thenReturn(List.of());
        when(patientService.profiles()).thenReturn(List.of());
        when(patientService.activityLogs()).thenReturn(List.of());
        when(patientService.medications()).thenReturn(List.of());
        when(patientService.reports()).thenReturn(List.of());
        service = new PatientDirectoryService(taskRepository, profileRepository, patientService);
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
            List.of(new ClinicalCase("case-1", "patient-case", Instant.now(), null, "brief", "OPEN", PROFESSIONAL_ID))
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
            List.of(new ClinicalCase("case-1", "patient-other", Instant.now(), null, "brief", "OPEN", "a-different-professional"))
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
        when(patientService.profiles()).thenReturn(List.of(profile("patient-mine", "Ama", "Mensah", "female", LocalDate.of(1990, 1, 1))));

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
        when(profileRepository.findByAccountId(LOGIN)).thenReturn(Optional.empty());

        assertThat(service.directory()).isEmpty();
        assertThat(service.summary().patients()).isZero();
    }

    @Test
    void patientserviceBeingUnreachableEmptiesTheDirectoryRatherThanFailing() {
        // The client answers empty on failure by design, so this is what a sibling outage looks
        // like from here: a bare directory, not a 500 on a page the clinician could partly use.
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task("patient-mine")));
        when(patientService.profiles()).thenReturn(List.of());

        assertThat(service.directory()).isEmpty();
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

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 2), DirectoryFilter.NONE);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void thePastTheEndPageIsEmptyRatherThanOutOfBounds() {
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(9, 2), DirectoryFilter.NONE);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void theTotalIsTHEFILTEREDtotal_notTheWholeCaseload() {
        // Otherwise the client pages through a phantom: 3 total, 1 row, no second page to fetch.
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 20), new DirectoryFilter(null, "male", null));

        assertThat(page.getContent()).extracting(PatientListItem::id).containsExactly("p-b");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void theQueryMatchesNameOrId_becauseAWristbandCarriesTheId() {
        threePatients();

        assertThat(service.directory(PageRequest.of(0, 20), new DirectoryFilter("mensah", null, null)).getContent())
            .extracting(PatientListItem::id)
            .containsExactly("p-a");
        assertThat(service.directory(PageRequest.of(0, 20), new DirectoryFilter("P-C", null, null)).getContent())
            .extracting(PatientListItem::id)
            .containsExactly("p-c");
    }

    @Test
    void childrenOnlyUsesTheAgeComputedPerRead() {
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 20), new DirectoryFilter(null, null, true));

        assertThat(page.getContent()).extracting(PatientListItem::id).containsExactly("p-c");
    }

    @Test
    void filtersCOMBINE_theyDoNotWiden() {
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 20), new DirectoryFilter("akosua", "male", null));

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void aWhitelistedSortIsApplied() {
        threePatients();

        Page<PatientListItem> page = service.directory(PageRequest.of(0, 20, Sort.by("patientName")), DirectoryFilter.NONE);

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

        Page<PatientListItem> page = service.directory(Pageable.unpaged(), DirectoryFilter.NONE);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void aNullFilterMeansNoFilter() {
        threePatients();

        assertThat(service.directory(PageRequest.of(0, 20), null).getTotalElements()).isEqualTo(3);
    }
}
