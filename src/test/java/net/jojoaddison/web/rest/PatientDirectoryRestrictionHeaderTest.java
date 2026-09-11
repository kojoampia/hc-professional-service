package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.PatientWriteReceiptRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.PatientDirectoryService;
import net.jojoaddison.service.PatientDirectoryService.RestrictedPart;
import net.jojoaddison.service.PatientServiceClient;
import net.jojoaddison.service.PatientServiceUnavailableException;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * That a refused part of the directory reaches the client, and that nothing is paid for it when
 * nothing was refused (backlog item 107).
 *
 * <p><b>A unit test rather than a case in {@code PatientResourceIT}, deliberately.</b> The integration
 * suite needs a Docker daemon and Testcontainers, and on a loaded workstation it is the first thing to
 * time out (backlog item 28) — a guard on a one-line header that only runs when the machine is quiet is
 * a guard that is absent exactly when somebody is in a hurry. Everything this asserts is decided in
 * {@code PatientResource.list} and nothing in it needs a database, a broker or a sibling service.
 */
@ExtendWith(MockitoExtension.class)
class PatientDirectoryRestrictionHeaderTest {

    @Mock
    private PatientDirectoryService patientDirectoryService;

    /**
     * The four collaborators the real service needs, for the one test below that wires it up.
     *
     * <p>{@code receiptRepository} is never touched — no directory read files a write receipt — and is
     * a mock rather than a fake so that a read path which started writing one would fail loudly here.
     */
    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private PatientServiceClient patientService;

    @Mock
    private PatientWriteReceiptRepository receiptRepository;

    private PatientResource resource;

    @BeforeEach
    void setUp() {
        // PaginationUtil builds the Link header from the current request, so there has to be one.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/patients")));
        resource = new PatientResource(patientDirectoryService);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
        // The one test that authenticates a caller does so on the shared holder; leaving it set would
        // hand the next test in this JVM a technician it never asked for.
        SecurityContextHolder.clearContext();
    }

    private void answers(PatientDirectoryService.Directory directory) {
        when(patientDirectoryService.directory(any(), any())).thenReturn(directory);
    }

    private static PatientDirectoryService.Directory directory(java.util.Set<RestrictedPart> restrictions) {
        PatientListItem row = new PatientListItem("p-1", "Ama Mensah", null, "female", false);
        return new PatientDirectoryService.Directory(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1), restrictions);
    }

    /**
     * The ordinary case — five of the eight disciplines — and the one that should pay nothing. An
     * always-present header would make the value meaningless: a client cannot distinguish "nothing was
     * restricted" from "this field means nothing" if it is there every time.
     */
    @Test
    void nothingRestrictedMeansNoHeaderAtAll() {
        answers(directory(java.util.Set.of()));

        ResponseEntity<List<PatientListItem>> response = resource.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().headerNames()).doesNotContain(PatientResource.RESTRICTED_PARTS);
        assertThat(response.getBody()).extracting(PatientListItem::id).containsExactly("p-1");
    }

    /** A pharmacist or a chemist: the rows are all there and the blank recency column is explained. */
    @Test
    void aRestrictedPartIsNAMEDonTheResponse() {
        answers(directory(java.util.EnumSet.of(RestrictedPart.LAST_ACTIVITY)));

        ResponseEntity<List<PatientListItem>> response = resource.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("lastActivity");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * Two at once, in declaration order.
     *
     * <p>The order is asserted because it is the difference between a header a client can compare
     * between requests and one that reshuffles for no reason — the reason
     * {@code PatientDirectoryService} collects these in an {@code EnumSet} rather than a {@code HashSet}.
     */
    @Test
    void severalRestrictedPartsAreListedInAStableOrder() {
        answers(directory(java.util.EnumSet.of(RestrictedPart.LAST_ACTIVITY, RestrictedPart.CASE_ASSIGNMENTS)));

        ResponseEntity<List<PatientListItem>> response = resource.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("caseAssignments,lastActivity");
    }

    /**
     * The wire name is the {@code token()}, not the enum constant. A client reads this string, so
     * renaming the constant must not rename the contract — and {@code toString()} would have done
     * exactly that, silently and at the moment somebody tidied an enum.
     */
    @Test
    void theWireNameIsTheTokenAndNotTheEnumConstant() {
        answers(directory(java.util.EnumSet.of(RestrictedPart.CASE_ASSIGNMENTS)));

        assertThat(resource.list(PageRequest.of(0, 20), null, null, null).getHeaders().getFirst(PatientResource.RESTRICTED_PARTS))
            .isEqualTo("caseAssignments")
            .isNotEqualTo(RestrictedPart.CASE_ASSIGNMENTS.name());
    }

    /**
     * The same header, built by the <b>real service</b> from two real refusals — the case the four
     * tests above cannot reach and the one the ordering defect lived in.
     *
     * <p><b>Why a second wiring rather than another stubbed {@code Directory}.</b> Each test above
     * hands the resource a {@code Directory} constructed in the test, so the set it renders is the set
     * the test built; the defect was in the copy the <em>service</em> makes on the way out, which no
     * stub crosses. {@code Set.copyOf} — what the service used until this review — yields an
     * {@code ImmutableCollections.Set12} for two elements, whose iteration order is decided by a
     * per-JVM {@code SALT}: measured over ten JVM starts on this project's JDK, six runs gave
     * {@code caseAssignments,lastActivity} and four gave the reverse, under a javadoc promising the
     * value was stable.
     *
     * <p><b>The input is the one discipline the item measured.</b> A technician is refused
     * {@code /api/clinical-cases} and {@code /api/activity-logs} alike, so with at least one task they
     * are the only caller in the estate for whom both parts are restricted at once — and the task is
     * load-bearing, because with none the composition returns before the activity log is asked for.
     *
     * <p><b>Honest about what this catches.</b> The literal is asserted rather than re-derived from the
     * enum, so a regression to {@code Set.copyOf} reddens it — but only on the JVM starts whose salt
     * happens to reverse the pair, which was four in ten when measured. It is a guard that bites
     * roughly half the time rather than a deterministic one; making it deterministic would mean
     * controlling the salt, which is not something a test should reach for.
     */
    @Test
    void bothPartsRefusedRenderINdeclarationOrder_throughTheRealService() {
        String accountId = "uid-tech";
        String professionalId = "professional-tech";
        SecurityContextHolder.getContext().setAuthentication(WithMockGatewayUser.Factory.authenticationFor("technician", accountId));

        Profile mine = new Profile();
        mine.setId(professionalId);
        mine.setAccountId(accountId);
        when(profileRepository.findByAccountId(accountId)).thenReturn(Optional.of(mine));

        Task visit = new Task();
        visit.setAttendantId(professionalId);
        visit.setPatientId("p-task");
        when(taskRepository.findByAttendantId(professionalId)).thenReturn(List.of(visit));

        when(patientService.clinicalCases()).thenThrow(refused("/api/clinical-cases"));
        when(patientService.activityLogs()).thenThrow(refused("/api/activity-logs"));
        when(patientService.profiles()).thenReturn(
            List.of(
                new PatientProfile(
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
                )
            )
        );

        PatientResource real = new PatientResource(
            new PatientDirectoryService(taskRepository, profileRepository, patientService, receiptRepository)
        );

        ResponseEntity<List<PatientListItem>> response = real.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("caseAssignments,lastActivity");
        // The directory itself still answers, which is the item: the task half survives both refusals.
        assertThat(response.getBody()).extracting(PatientListItem::id).containsExactly("p-task");
    }

    private static PatientServiceUnavailableException refused(String path) {
        return PatientServiceUnavailableException.read(path, PatientServiceUnavailableException.Fault.UPSTREAM_FORBIDDEN, "Forbidden");
    }
}
