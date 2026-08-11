package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
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
        return new PatientProfile("profile-" + patientId, patientId, first, null, last, born, sex, "024", null, "p@example.com", null);
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
}
