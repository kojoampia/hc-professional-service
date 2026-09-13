package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.PatientWriteReceiptRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.PatientDirectoryService.DirectoryFilter;
import net.jojoaddison.service.PatientDirectoryService.RestrictedPart;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * That losing <em>patients</em> from the directory is distinguishable, in production, from losing a
 * <em>column</em> (backlog item 116).
 *
 * <p><b>Driven through the real {@link PatientDirectoryService}, not through the meters class
 * directly.</b> The defect was never that the recording was wrong — there was no recording. It was
 * that the two degrade branches produced the same {@code log.debug}, which {@code application-prod.yml}
 * does not emit at all. So every case here starts from a refusal on a stubbed
 * {@link PatientServiceClient} and asserts what a production operator would have to go on; a test that
 * called {@code record(…)} with a hand-built set would pass against a service that never called it.
 *
 * <p><b>The positive control is {@link #anENTITLEDcallerLeavesNOseriesAtAll()} and it carries more
 * weight here than usual.</b> Every other case asserts that <em>something</em> was emitted, and a
 * change that emitted unconditionally would satisfy all of them. It is also asserting a property
 * rather than a convenience: a Micrometer counter that has never been incremented produces no series
 * through this estate's OTLP push (backlog item 97), so "no series" is the steady state an alert on a
 * <em>new</em> series depends on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientDirectoryRestrictionMetersTest {

    private static final String PROFESSIONAL_ID = "professional-1";

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private PatientServiceClient patientService;

    @Mock
    private PatientWriteReceiptRepository receiptRepository;

    private SimpleMeterRegistry registry;
    private PatientDirectoryService service;
    private ListAppender<ILoggingEvent> logged;
    private ch.qos.logback.classic.Logger metersLogger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // A fresh meters bean per test, so the once-per-process announcement set starts empty — the
        // restart behaviour the class javadoc describes, exercised rather than assumed.
        service = new PatientDirectoryService(
            taskRepository,
            profileRepository,
            patientService,
            receiptRepository,
            new PatientDirectoryRestrictionMeters(registry)
        );

        // The whole fixture is stubbed once, here, and a case that wants a refusal replaces one read
        // with `doThrow`. That asymmetry is deliberate: `when(patientService.clinicalCases())` CALLS
        // the mock, so a second `when(...)` on a read a previous line has already taught to throw
        // raises out of the fixture rather than re-stubbing it — which is exactly what happened when
        // the sign-in helper below stubbed its own data.
        when(taskRepository.findByAttendantId(anyString())).thenReturn(List.of());
        Task visit = new Task();
        visit.setAttendantId(PROFESSIONAL_ID);
        visit.setPatientId("p-task");
        when(taskRepository.findByAttendantId(PROFESSIONAL_ID)).thenReturn(List.of(visit));
        when(patientService.profiles()).thenReturn(List.of(patientProfile()));
        when(patientService.clinicalCases()).thenReturn(List.of(aCase()));
        when(patientService.activityLogs()).thenReturn(List.of(anActivity()));

        metersLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PatientDirectoryRestrictionMeters.class);
        logged = new ListAppender<>();
        logged.start();
        metersLogger.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        metersLogger.detachAppender(logged);
        logged.stop();
        // The security context is the shared holder; leaving a technician on it would hand the next
        // test in this JVM a caller it never asked for.
        SecurityContextHolder.clearContext();
    }

    // --- Fixture -------------------------------------------------------------------------------

    /**
     * Signs in a caller of the given discipline and gives them the one profile every case here shares.
     *
     * <p>Identity only — the patientservice reads are stubbed once in {@code setUp}. Every caller sees
     * the same directory, because the subject is which <em>discipline</em> a refusal is attributed to
     * and nothing about the rows.
     */
    private void callerIsA(String login, String... authorities) {
        String accountId = WithMockGatewayUser.Factory.accountIdFor(login);
        SecurityContextHolder.getContext().setAuthentication(WithMockGatewayUser.Factory.authenticationFor(login, accountId, authorities));
        Profile mine = new Profile();
        mine.setId(PROFESSIONAL_ID);
        mine.setAccountId(accountId);
        when(profileRepository.findByAccountId(accountId)).thenReturn(Optional.of(mine));
    }

    /** hc-patient refuses one read outright. {@code doThrow}, so re-stubbing never calls the mock. */
    private void refusesTheCaseCollection() {
        org.mockito.Mockito.doThrow(refused("/api/clinical-cases")).when(patientService).clinicalCases();
    }

    private void refusesTheActivityLog() {
        org.mockito.Mockito.doThrow(refused("/api/activity-logs")).when(patientService).activityLogs();
    }

    private static PatientProfile patientProfile() {
        return new PatientProfile(
            "profile-p-task",
            "p-task",
            "Ama",
            null,
            "Mensah",
            LocalDate.of(1990, 1, 1),
            "female",
            "024",
            null,
            "p@example.com",
            null,
            null
        );
    }

    private static ClinicalCase aCase() {
        return new ClinicalCase(
            "c-1",
            "p-case",
            1,
            "Title",
            Instant.parse("2026-09-01T09:00:00Z"),
            null,
            "brief",
            "OPEN",
            null,
            null,
            PROFESSIONAL_ID,
            null,
            null
        );
    }

    private static ActivityLog anActivity() {
        return new ActivityLog(
            "al-1",
            "p-task",
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

    /** hc-patient answering 403 — keyed on the {@code Fault}, as the item 107 tests are. */
    private static PatientServiceUnavailableException refused(String path) {
        return PatientServiceUnavailableException.read(path, PatientServiceUnavailableException.Fault.UPSTREAM_FORBIDDEN, "Forbidden");
    }

    private static PatientServiceUnavailableException outage(String path) {
        return PatientServiceUnavailableException.read(path, PatientServiceUnavailableException.Fault.TRANSPORT, "connection refused");
    }

    private void readTheDirectory() {
        service.directory(PageRequest.of(0, 20), DirectoryFilter.NONE);
    }

    private Counter counter(String part, String discipline) {
        return registry
            .find(PatientDirectoryRestrictionMeters.METER_NAME)
            .tag(PatientDirectoryRestrictionMeters.PART_TAG, part)
            .tag(PatientDirectoryRestrictionMeters.DISCIPLINE_TAG, discipline)
            .counter();
    }

    private List<String> warnings() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    // --- The contract --------------------------------------------------------------------------

    /**
     * <b>The meter name, its base unit and its tag keys, asserted as literals.</b>
     *
     * <p>These are what a Grafana panel and an alert rule name, and a rename breaks both silently —
     * nothing in a dashboard fails a build. {@code gateway/} keeps a {@code MeterScrapeNamesUnitTest}
     * for exactly this reason; {@code api/} has no equivalent, so the assertion is made here, at the
     * one meter this change introduces.
     *
     * <p>The tag <em>keys</em> are asserted as a complete set rather than individually: adding a third
     * dimension multiplies the series a panel has to aggregate away and is a contract change too.
     */
    @Test
    void theMeterNameBaseUnitAndTagKeysAreTheContract() {
        callerIsA("tech", "ROLE_TECHNICIAN");
        refusesTheCaseCollection();

        readTheDirectory();

        Meter.Id id = counter("caseAssignments", "technician").getId();
        assertThat(id.getName()).isEqualTo("patient.directory.restricted");
        assertThat(id.getBaseUnit()).isEqualTo("reads");
        assertThat(id.getTags()).extracting(Tag::getKey).containsExactlyInAnyOrder("part", "discipline");
    }

    // --- Rows and columns are told apart --------------------------------------------------------

    /**
     * A technician: the case collection is refused, so <b>patients</b> are missing from the list.
     *
     * <p>The row-dropping part is counted under its own {@code part} value, and the column part has no
     * series at all — which is the whole item. Both halves are asserted, because a counter that
     * incremented under one shared tag value would satisfy the first.
     */
    @Test
    void aROWdroppingRefusalIsCountedUnderItsOwnPart() {
        callerIsA("tech", "ROLE_TECHNICIAN");
        refusesTheCaseCollection();

        readTheDirectory();

        assertThat(counter("caseAssignments", "technician").count()).isEqualTo(1);
        assertThat(counter("lastActivity", "technician")).isNull();
    }

    /** A pharmacist: the activity log is refused, every patient is still listed. The mirror image. */
    @Test
    void aCOLUMNblankingRefusalIsCountedUnderADIFFERENTpart() {
        callerIsA("pharm", "ROLE_PHARMACIST");
        refusesTheActivityLog();

        readTheDirectory();

        assertThat(counter("lastActivity", "pharmacist").count()).isEqualTo(1);
        assertThat(counter("caseAssignments", "pharmacist")).isNull();
    }

    /**
     * Both at once — a technician with at least one task, the only caller in the estate for whom that
     * happens — produces <b>two distinct series</b> rather than one count of "something was refused".
     */
    @Test
    void bothPartsRefusedProduceTWOseriesNotOne() {
        callerIsA("tech", "ROLE_TECHNICIAN");
        refusesTheCaseCollection();
        refusesTheActivityLog();

        readTheDirectory();

        assertThat(counter("caseAssignments", "technician").count()).isEqualTo(1);
        assertThat(counter("lastActivity", "technician").count()).isEqualTo(1);
    }

    /**
     * <b>The positive control.</b> Five of the eight disciplines are refused nothing, and they are
     * almost every request: an entitled caller must leave no series behind at all.
     *
     * <p>Asserted as the absence of the <em>meter</em>, not as a count of zero, and the difference is
     * the point. A registered counter sitting at zero would push a {@code discipline="doctor"} series
     * from every replica on startup, and the alert this instrumentation exists for — a series
     * appearing that was not there before — would never fire again.
     */
    @Test
    void anENTITLEDcallerLeavesNOseriesAtAll() {
        callerIsA("doc", "ROLE_DOCTOR");

        readTheDirectory();

        assertThat(registry.find(PatientDirectoryRestrictionMeters.METER_NAME).meters()).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    /**
     * <b>The motivating scenario, spelled out.</b> hc-patient's matrix stops admitting
     * {@code ROLE_DOCTOR} to {@code /api/clinical-cases}, and every doctor's directory silently loses
     * every case-only patient behind a 200.
     *
     * <p>What makes it visible is that the discipline is a tag: the series is a <em>new</em> one, next
     * to the {@code technician} series that was always there. Asserted together so that a change
     * collapsing the two into one dimension reddens here rather than in a dashboard six months later.
     */
    @Test
    void aDRIFTEDmatrixRefusingADOCTORmintsAdistinctSeries() {
        callerIsA("tech", "ROLE_TECHNICIAN");
        refusesTheCaseCollection();
        readTheDirectory();

        SecurityContextHolder.clearContext();
        callerIsA("doc", "ROLE_DOCTOR");
        refusesTheCaseCollection();
        readTheDirectory();

        assertThat(counter("caseAssignments", "technician").count()).isEqualTo(1);
        assertThat(counter("caseAssignments", "doctor").count()).isEqualTo(1);
    }

    // --- A real outage is not a restriction ------------------------------------------------------

    /**
     * <b>The thing most at risk.</b> A sibling that is genuinely down still 503s, and must not be
     * reported as a discipline being refused — an operator told "the scope matrix withheld this" goes
     * looking at hc-patient's authorities, which is the wrong three places all over again.
     *
     * <p>Both reads are exercised separately: one tolerant catch left too wide would be caught by
     * either, but one of the two left wide would not.
     */
    @Test
    void anOUTAGEonTheCaseCollectionIsNOTrecordedAsARestriction() {
        callerIsA("doc", "ROLE_DOCTOR");
        org.mockito.Mockito.doThrow(outage("/api/clinical-cases")).when(patientService).clinicalCases();

        assertThatThrownBy(this::readTheDirectory).isInstanceOf(PatientServiceUnavailableException.class);

        assertThat(registry.find(PatientDirectoryRestrictionMeters.METER_NAME).meters()).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void anOUTAGEonTheActivityLogIsNOTrecordedAsARestriction() {
        callerIsA("doc", "ROLE_DOCTOR");
        org.mockito.Mockito.doThrow(outage("/api/activity-logs")).when(patientService).activityLogs();

        assertThatThrownBy(this::readTheDirectory).isInstanceOf(PatientServiceUnavailableException.class);

        assertThat(registry.find(PatientDirectoryRestrictionMeters.METER_NAME).meters()).isEmpty();
    }

    /**
     * A request refused for an unsortable {@code sort=} is a 400 that served no directory, so it
     * counts nothing — even though a part was genuinely withheld while assembling it.
     *
     * <p>A consequence of recording where the {@code Directory} is built rather than in the degrade
     * branches, and asserted so that it is a decision on the record rather than an accident of
     * placement: the meter counts <em>directories served short of a part</em>, which is what an
     * operator reading a rate wants, and not refusals encountered.
     */
    @Test
    void aREJECTEDsortCountsNothing_becauseNoDirectoryWasServed() {
        callerIsA("tech", "ROLE_TECHNICIAN");
        refusesTheCaseCollection();

        assertThatThrownBy(
            () -> service.directory(PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("dropTable")), DirectoryFilter.NONE)
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.find(PatientDirectoryRestrictionMeters.METER_NAME).meters()).isEmpty();
    }

    // --- The log half ----------------------------------------------------------------------------

    /**
     * The row-dropping branch says so at <b>WARN</b>, which {@code net.jojoaddison: INFO} emits; the
     * column branch says nothing above DEBUG, which it does not.
     *
     * <p>That asymmetry is the item. It is asserted on the level rather than on the presence of a log
     * event, because both branches still carry their own {@code log.debug} and a test that merely
     * counted events would pass while both were invisible in production.
     */
    @Test
    void theROWdroppingBranchWARNSandTheCOLUMNbranchDoesNot() {
        callerIsA("pharm", "ROLE_PHARMACIST");
        refusesTheActivityLog();
        readTheDirectory();

        assertThat(warnings()).isEmpty();

        SecurityContextHolder.clearContext();
        callerIsA("tech", "ROLE_TECHNICIAN");
        refusesTheCaseCollection();
        readTheDirectory();

        assertThat(warnings()).singleElement().asString().contains("caseAssignments").contains("technician").contains("tech");
    }

    /**
     * Once per {@code (part, discipline)} per process, and a second discipline announces separately.
     *
     * <p><b>This is the whole of the volume argument.</b> A refused discipline makes this call on
     * every directory load, so an unconditional WARN would be back to per-request noise about a rule
     * working as designed — the thing item 107 removed. Three reads by two technicians produce one
     * line; the doctor of the drift scenario produces a second, immediately, which is what has to
     * survive the deduplication.
     */
    @Test
    void theWARNisEmittedONCEperDisciplinePerProcess() {
        callerIsA("tech", "ROLE_TECHNICIAN");
        refusesTheCaseCollection();
        readTheDirectory();
        readTheDirectory();
        readTheDirectory();

        assertThat(warnings()).hasSize(1);
        assertThat(counter("caseAssignments", "technician").count()).isEqualTo(3);

        SecurityContextHolder.clearContext();
        callerIsA("doc", "ROLE_DOCTOR");
        refusesTheCaseCollection();
        readTheDirectory();

        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(1)).contains("doctor");
    }

    // --- The discipline tag ----------------------------------------------------------------------

    /**
     * A caller holding none of the nine known authorities is tagged, not left dimensionless.
     *
     * <p>Reachable by an applicant with {@code ROLE_USER} alone and by a token from a sibling stack.
     * A series missing a dimension the rest carry breaks a {@code by (discipline)} aggregation
     * silently, which is why there is a value for it.
     */
    @Test
    void aCallerWithNOknownDisciplineIsTaggedRatherThanUntagged() {
        callerIsA("applicant", "ROLE_USER");
        refusesTheCaseCollection();

        readTheDirectory();

        assertThat(counter("caseAssignments", "none").count()).isEqualTo(1);
    }

    /**
     * The tag value comes from {@link net.jojoaddison.security.AuthoritiesConstants#CLINICAL_AND_ADMIN}
     * and cannot be set by the caller's token.
     *
     * <p>An authority nobody here declares is not a discipline, and minting a series for it would put
     * an unbounded, caller-controlled dimension into a meter. {@code ROLE_ANGEL} is the literal used
     * because it is the one this subsystem deliberately stopped naming (item 44) while still receiving
     * tokens that carry it.
     */
    @Test
    void anUNKNOWNauthorityCannotMintItsOwnSeries() {
        callerIsA("angel", "ROLE_ANGEL");
        refusesTheCaseCollection();

        readTheDirectory();

        assertThat(counter("caseAssignments", "angel")).isNull();
        assertThat(counter("caseAssignments", "none").count()).isEqualTo(1);
    }

    /**
     * {@code removesRows()} is what the escalation keys on, and it is stated on the enum rather than
     * as a constant in the meters class so a third part has to answer the question beside the
     * description of what its absence costs.
     */
    @Test
    void theEnumIsWhereTheAsymmetryIsDeclared() {
        assertThat(RestrictedPart.CASE_ASSIGNMENTS.removesRows()).isTrue();
        assertThat(RestrictedPart.LAST_ACTIVITY.removesRows()).isFalse();
    }
}
