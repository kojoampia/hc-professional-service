package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.PatientWriteReceiptRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.PatientDirectoryRestrictionMeters;
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
 * nothing was refused (backlog item 107) — and, since backlog item 128, whether the rows it served
 * will open at all.
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

    // --- And whether the rows open at all (backlog item 128) ---------------------------------

    /**
     * A technician: the cases collection is refused, so nothing in the list they were just handed will
     * open — and the response says so before they tap one.
     *
     * <p>The status and the rows are asserted beside the header for the reason the record tests below
     * are: a response that had started announcing the problem by <em>withholding the directory</em> would
     * satisfy a header-only assertion, and that is the outcome item 128 considered and rejected. The list
     * is the patients this technician is scheduled to attend; it is worth serving whether or not the
     * records behind it are theirs.
     */
    @Test
    void aDirectoryWhoseROWSwillNotOpenSaysSo() {
        answers(directory(java.util.EnumSet.of(RestrictedPart.CASE_ASSIGNMENTS, RestrictedPart.LAST_ACTIVITY)));

        ResponseEntity<List<PatientListItem>> response = resource.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_FOLLOW_UPS)).isEqualTo("record");
        assertThat(response.getBody()).extracting(PatientListItem::id).containsExactly("p-1");
    }

    /**
     * <b>A pharmacist must not be told this, and this is the assertion that keeps the header honest.</b>
     *
     * <p>They lose the recency column and open every record perfectly well (backlog item 112). A marker
     * keyed on "some part was restricted" rather than on <em>which</em> part would fire here and tell a
     * pharmacist that a hundred rows they can read are closed to them — a false sentence in the opposite
     * direction from the one item 107 removed, which is the failure mode this backlog has recorded three
     * times inside its own fixes.
     */
    @Test
    void aRestrictionThatDoesNOTblockTheRecordDoesNOTclaimTheRowsAreClosed() {
        answers(directory(java.util.EnumSet.of(RestrictedPart.LAST_ACTIVITY)));

        ResponseEntity<List<PatientListItem>> response = resource.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("lastActivity");
        assertThat(response.getHeaders().headerNames()).doesNotContain(PatientResource.RESTRICTED_FOLLOW_UPS);
    }

    /** A caller refused nothing pays nothing for this header either, which is the ordinary request. */
    @Test
    void nothingRestrictedMeansNoFollowUpHeaderEither() {
        answers(directory(java.util.Set.of()));

        assertThat(resource.list(PageRequest.of(0, 20), null, null, null).getHeaders().headerNames()).doesNotContain(
            PatientResource.RESTRICTED_FOLLOW_UPS
        );
    }

    /**
     * <b>The record response carries no follow-up marker, and that is a decision.</b> {@code X-Restricted-Parts}
     * is shared by both endpoints because the refused collection is the same collection; this one is not,
     * because it answers "what does this list lead to" and a record leads nowhere the same refusal
     * governs — {@code /api/patients/&#123;id&#125;/cases} is open as backlog item 127 and would have to
     * be unpicked whichever way that goes.
     */
    @Test
    void theRECORDresponseDoesNOTcarryAFollowUpMarker() {
        answersRecord(java.util.EnumSet.of(RestrictedPart.LAST_ACTIVITY));

        assertThat(resource.get("p-1").getHeaders().headerNames()).doesNotContain(PatientResource.RESTRICTED_FOLLOW_UPS);
    }

    // --- The same header on one patient's record (backlog item 112) --------------------------

    private static net.jojoaddison.service.dto.PatientDtos.PatientRecord aRecord() {
        return new net.jojoaddison.service.dto.PatientDtos.PatientRecord(
            "p-1",
            "Ama Mensah",
            null,
            "female",
            false,
            "1990-01-01",
            "024",
            "p@example.invalid",
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private void answersRecord(java.util.Set<RestrictedPart> restrictions) {
        when(patientDirectoryService.recordWithinScope(any())).thenReturn(
            Optional.of(new PatientDirectoryService.RecordView(aRecord(), restrictions))
        );
    }

    /**
     * The ordinary case on the record too: six of the eight disciplines read every part of one, and pay
     * nothing for the two that cannot.
     */
    @Test
    void aRecordWithNothingRestrictedCarriesNoHeader() {
        answersRecord(java.util.Set.of());

        ResponseEntity<net.jojoaddison.service.dto.PatientDtos.PatientRecord> response = resource.get("p-1");

        assertThat(response.getHeaders().headerNames()).doesNotContain(PatientResource.RESTRICTED_PARTS);
        assertThat(response.getBody().id()).isEqualTo("p-1");
    }

    /**
     * A pharmacist opening a patient: 200 with the record, and the withheld panel named.
     *
     * <p>Item 107 left this at 503, so the directory it unblocked was a list of dead ends (backlog item
     * 112). The status is asserted beside the header because the header alone would be green on a
     * response that had kept the 503 and merely started announcing why.
     */
    @Test
    void aRestrictedPartIsNAMEDonTheRecordResponse() {
        answersRecord(java.util.EnumSet.of(RestrictedPart.LAST_ACTIVITY));

        ResponseEntity<net.jojoaddison.service.dto.PatientDtos.PatientRecord> response = resource.get("p-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("lastActivity");
        assertThat(response.getBody()).isNotNull();
    }

    /**
     * <b>The body is still the record and not an envelope</b>, which is the whole reason a header was
     * chosen (item 111 Decision A). {@code web/} and {@code mobile/} deserialise this response as the
     * record object; a wrapper naming the restriction would break both on the release that shipped it,
     * and a header cannot. Asserted rather than trusted, because the change that adds an envelope looks
     * exactly like a tidy-up from inside this repository.
     */
    @Test
    void theRecordBodyIsUnchangedByTheRestriction() {
        answersRecord(java.util.EnumSet.of(RestrictedPart.LAST_ACTIVITY));

        assertThat(resource.get("p-1").getBody()).isEqualTo(aRecord());
    }

    /**
     * A patient that is not the caller's is still a 404, restriction machinery or not. The tolerant path
     * and the authorization boundary are different questions, and this is what keeps them apart at the
     * resource: {@code Optional.empty()} must not become a 200 carrying a record-shaped nothing.
     */
    @Test
    void aPatientOutsideTheCaseloadIsStillA404() {
        when(patientDirectoryService.recordWithinScope(any())).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resource.get("p-other"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("404");
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

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PatientResource real = new PatientResource(
            new PatientDirectoryService(
                taskRepository,
                profileRepository,
                patientService,
                receiptRepository,
                new PatientDirectoryRestrictionMeters(registry)
            )
        );

        ResponseEntity<List<PatientListItem>> response = real.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("caseAssignments,lastActivity");
        // The directory itself still answers, which is the item: the task half survives both refusals.
        assertThat(response.getBody()).extracting(PatientListItem::id).containsExactly("p-task");

        // And the one row it answers with will not open (backlog item 128). Asserted here as well as in
        // the stubbed cases above because this is the only test that crosses the whole chain with a real
        // service behind it: a real refusal, a real EnumSet, a real Directory, and the derivation the
        // resource makes from it. The stubs hand the resource a restriction set the test built; what
        // this adds is that a refusal actually reaching the service produces one.
        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_FOLLOW_UPS)).isEqualTo("record");

        // The metric and the header name the same parts (backlog item 116).
        //
        // Structurally true today — one `record(restrictions)` call, `new Directory(...)` on the very
        // next line over the same EnumSet instance, and one caller rendering the header from
        // `directory.restrictions()` — so there is no divergence path to find. Asserted all the same,
        // because "there is no divergence path" is a fact about where two lines currently sit rather
        // than a property anything holds, and this is the only test in the suite that crosses both
        // boundaries with a real service behind it. Without it the registry above would be a
        // collaborator nothing reads, which is how a recording quietly stops happening.
        List<String> metered = registry
            .find(PatientDirectoryRestrictionMeters.METER_NAME)
            .counters()
            .stream()
            .map(counter -> counter.getId().getTag(PatientDirectoryRestrictionMeters.PART_TAG))
            .sorted()
            .toList();
        List<String> headed = java.util.Arrays.stream(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS).split(","))
            .sorted()
            .toList();
        assertThat(metered).isEqualTo(headed).hasSize(2);
    }

    /**
     * <b>The marker is emitted on an empty page, and that is reachable rather than theoretical</b> — a
     * technician with no {@code Task} rows at all (found by the review of PR #51).
     *
     * <p>The path: the case collection is refused, so {@code patientIdsWithinScope} records
     * {@code CASE_ASSIGNMENTS} and falls back to the task half, which is empty, so {@code withinScope}
     * returns <em>before the activity log is ever read</em>. The result is one restriction, zero rows,
     * and {@code X-Restricted-Follow-Ups: record} over nothing. Note what is asserted below beside it:
     * {@code X-Restricted-Parts} is {@code caseAssignments} <b>alone</b>, where a technician with one
     * task gets both parts — the caseload-dependence {@code RestrictedPart}'s own javadoc warns about,
     * reached here by a second route.
     *
     * <p><b>It is correct on this header's own terms and it is a trap for a client.</b> The claim is
     * <em>a part was withheld from this read that the record endpoint requires</em>, which is as true of
     * an empty page as of a full one — and the empty page is not vacuous, because the same technician
     * <em>does</em> have patients they simply cannot be shown, the case half being what would have
     * listed them. But the sentence a client renders from it — "the records behind these rows are not
     * yours to open" — is a banner over an empty list. <b>A client must key the banner on having rows
     * to describe</b>, exactly as item 114 keyed {@code caseAssignments} on the list rather than on a
     * row; the empty-and-restricted case wants "some patients cannot be shown here", which is
     * {@code X-Restricted-Parts}'s sentence and not this one. Asserted here rather than fixed here,
     * because suppressing the header on an empty page would make the wire value depend on the caller's
     * caseload — and a client that cached it (as {@code mobile/} caches the restricted set beside page
     * zero) would then see it appear and disappear as shifts are assigned.
     */
    @Test
    void anEmptyPageStillCarriesTheMarker_whichIsTheClientsToRenderCarefully() {
        String accountId = "uid-tech-notasks";
        String professionalId = "professional-tech-notasks";
        SecurityContextHolder.getContext().setAuthentication(WithMockGatewayUser.Factory.authenticationFor("technician", accountId));

        Profile mine = new Profile();
        mine.setId(professionalId);
        mine.setAccountId(accountId);
        when(profileRepository.findByAccountId(accountId)).thenReturn(Optional.of(mine));
        // No tasks: the half that survives the refusal is empty, which is the whole of this case.
        when(taskRepository.findByAttendantId(professionalId)).thenReturn(List.of());
        when(patientService.clinicalCases()).thenThrow(refused("/api/clinical-cases"));

        PatientResource real = new PatientResource(
            new PatientDirectoryService(
                taskRepository,
                profileRepository,
                patientService,
                receiptRepository,
                new PatientDirectoryRestrictionMeters(new SimpleMeterRegistry())
            )
        );

        ResponseEntity<List<PatientListItem>> response = real.list(PageRequest.of(0, 20), null, null, null);

        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_FOLLOW_UPS)).isEqualTo("record");
        // One part, not two: the activity log was never asked for, so it was never refused.
        assertThat(response.getHeaders().getFirst(PatientResource.RESTRICTED_PARTS)).isEqualTo("caseAssignments");
    }

    private static PatientServiceUnavailableException refused(String path) {
        return PatientServiceUnavailableException.read(path, PatientServiceUnavailableException.Fault.UPSTREAM_FORBIDDEN, "Forbidden");
    }
}
