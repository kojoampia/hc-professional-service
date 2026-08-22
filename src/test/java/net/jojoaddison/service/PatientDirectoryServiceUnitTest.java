package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private InMemoryReceiptRepository receiptRepository;
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

    // --- Writes (web-mobile-port.md § Phase 1.3) ----------------------------------------------

    private static final String MINE = "p-mine";

    /** One patient in the caller's caseload, so a write has somewhere legitimate to land. */
    private void onePatientOfMine() {
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(task(MINE)));
        when(patientService.profiles()).thenReturn(List.of(profile(MINE, "Ama", "Mensah", "female", LocalDate.of(1990, 1, 1))));
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
        when(patientService.activityLogs()).thenReturn(List.of(createdLog("al-1", "Wound dressed")));

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
        when(patientService.clinicalCases()).thenReturn(List.of(aCase("c-theirs", "p-other", "OPEN", "someone-else")));

        assertThatThrownBy(
            () -> service.updateCase("p-other", "c-theirs", new PatientDirectoryService.CaseUpdate("x", null, null, null))
        ).isInstanceOf(PatientDirectoryService.PatientNotInCaseloadException.class);
        verify(patientService, org.mockito.Mockito.never()).patchClinicalCase(any(), any());
    }

    @Test
    void onlyTheFOUReditableFieldsAreForwarded() {
        // A whole-document PATCH would let a caller move a case to another patient or reassign it.
        onePatientOfMine();
        when(patientService.clinicalCases()).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));
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
        when(patientService.clinicalCases()).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));
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
        when(patientService.clinicalCases()).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));
        when(patientService.patchClinicalCase(any(), any())).thenReturn(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID));

        service.updateCase(MINE, "c1", new PatientDirectoryService.CaseUpdate("s", null, null, null));

        ArgumentCaptor<java.util.Map<String, Object>> body = ArgumentCaptor.forClass(java.util.Map.class);
        verify(patientService).patchClinicalCase(any(), body.capture());
        assertThat(body.getValue()).doesNotContainKey("diagnosis");
    }

    @Test
    void aPatientsCasesAreEntitlementChecked() {
        onePatientOfMine();
        when(patientService.clinicalCases()).thenReturn(List.of(aCase("c1", MINE, "OPEN", PROFESSIONAL_ID)));

        assertThat(service.casesFor(MINE, PageRequest.of(0, 20)).getContent()).extracting("id").containsExactly("c1");
        assertThatThrownBy(() -> service.casesFor("p-other", PageRequest.of(0, 20))).isInstanceOf(
            PatientDirectoryService.PatientNotInCaseloadException.class
        );
    }
}
