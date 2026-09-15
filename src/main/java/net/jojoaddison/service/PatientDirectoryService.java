package net.jojoaddison.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jojoaddison.domain.PatientWriteReceipt;
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.PatientWriteReceiptRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.dto.PatientDtos.ActivityLogEntry;
import net.jojoaddison.service.dto.PatientDtos.CaseDetail;
import net.jojoaddison.service.dto.PatientDtos.CaseSummary;
import net.jojoaddison.service.dto.PatientDtos.ClinicalReport;
import net.jojoaddison.service.dto.PatientDtos.CreateActivity;
import net.jojoaddison.service.dto.PatientDtos.CreateReport;
import net.jojoaddison.service.dto.PatientDtos.DashboardSummary;
import net.jojoaddison.service.dto.PatientDtos.EmergencyContact;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import net.jojoaddison.service.dto.PatientDtos.PatientRecord;
import net.jojoaddison.service.dto.PatientDtos.RecordEntry;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The patients a clinician has worked with, and what is known about them.
 *
 * <p><strong>"Worked with" is the union of two assignments</strong>: a {@code Task} in this service
 * naming the clinician as attendant, and a {@code ClinicalCase} in patientservice naming them as the
 * assigned professional. Either alone would be wrong — a clinician can be handed a case without a
 * scheduled task, and can be scheduled against a patient who has no open case.
 *
 * <p><strong>The union is also the authorization boundary.</strong> {@link #record(String)} refuses a
 * patient outside it, so the endpoint cannot be used to read an arbitrary patient by guessing an id.
 * That check lives here rather than in the resource because the same rule has to hold for every
 * caller of this service, and because patientservice cannot enforce it — from its side these are
 * ordinary reads by an authenticated clinician.
 *
 * <p>Ages, and therefore {@code isChild}, are computed from the birth date on each read rather than
 * stored. A stored flag is wrong the day after it is written.
 *
 * <p><strong>An outage in patientservice is a 503 here, never a caseload decision</strong> (backlog
 * item 24). Every entitlement answer this service gives is computed from a collection it reads across
 * the wire, so while {@link PatientServiceClient} degraded an unreadable collection to an empty list,
 * a clinician standing next to a patient during a sibling outage was told <em>this patient is not in
 * your caseload</em> — and an administrator reading the same screen concluded the assignment was
 * missing rather than that a service was down. The client now raises
 * {@link PatientServiceUnavailableException} for a read that did not happen and answers empty only for
 * a collection that is genuinely empty, and <b>this service lets that exception through</b>: nothing
 * below catches it, so {@code ExceptionTranslator} turns it into a 503. That is the difference between
 * "call the office about your caseload" and "wait five minutes", and it is why the checks in
 * {@link #record(String)} and {@link #requireEntitlement(String)} may never be reached on a failed
 * read. {@code DutyRosterService} answers the same signal differently and is equally right — it has
 * stored snapshots to serve; this service has nothing to serve but the answer it could not get.
 *
 * <p><strong>A read the caller's discipline may never make is not an outage, and answering 503 for it
 * is the same conflation one step out</strong> (backlog item 107). The paragraph above is about a read
 * that <em>failed</em>; hc-patient also answers reads that <em>succeed at refusing</em>. It owns the
 * scope-of-practice matrix, and a pharmacist, a chemist or a technician asking it for
 * {@code /api/activity-logs} gets a deliberate, permanent 403 — measured on the quality stack, all
 * eight disciplines, 2026-09-11. This service composed that read unconditionally, so three of the
 * eight disciplines lost <em>the whole patient directory</em> — their first screen — over one panel of
 * it they were never entitled to, under a message telling the operator it might clear on retry. Two
 * rules follow and they are stated where they are applied:
 *
 * <ul>
 *   <li><b>A refused part is named, never silently emptied.</b> "No activity" and "not yours to see"
 *       are different sentences, which is the distinction backlog items 78, 87 and 92 each drew at a
 *       different site; degrading a refusal to an empty panel would reintroduce it here. See
 *       {@link RestrictedPart} and {@link Directory}.
 *   <li><b>Only a read that <em>supplies</em> the directory degrades; a read that <em>decides</em>
 *       something still raises.</b> {@link #directory(Pageable, DirectoryFilter)} tolerates a refusal
 *       and marks it. {@link #casesFor}, {@link #caseDetail}, {@link #myCases} and both writes do not,
 *       and are unchanged: their answers are entitlements and case lists, and a list computed over a
 *       collection the caller was refused is a wrong answer presented as a fact.
 * </ul>
 *
 * <p><strong>Item 107 closed the directory and left every row in it a dead end; backlog item 112 is the
 * larger half.</strong> For the three refused disciplines the first screen loaded and nothing on it
 * opened — {@link #record} and {@link #summary} answered 503 by the same mechanism. Both now apply the
 * rule above rather than an exemption from it, and the rule sorts them out by itself:
 *
 * <ul>
 *   <li>{@link #recordWithinScope} loses the activity list, names it, and serves the record — for a
 *       pharmacist and a chemist, whose cases, medications, reports and demographics all still arrive. A
 *       <b>technician</b> is refused the collection {@link #entitledCases} decides entitlement from, so
 *       their record still raises; that javadoc carries the measurements and argues why a hollow record
 *       would be the worse answer.
 *   <li>{@link #summary} counts through {@code countableCaseload()}, which tolerates the one refusal that
 *       <b>changes none of its four numbers</b> and none of the ones that would. That is an argument
 *       against item 111's Decision C rather than an exception to it, and it is made there.
 * </ul>
 *
 * <p><strong>For a technician the rows still do not open, and the directory now says so</strong>
 * (backlog item 128). Item 112 refused to degrade their record and argued it well — hc-patient admits
 * them to no clinical domain, so what a degraded record could carry is a contact card with four empty
 * lists beside it. That argument is about the record; it leaves the <em>list</em> presenting a hundred
 * tappable rows of which not one opens, with nothing on the wire a client could mark them by.
 * {@link RestrictedPart#blocksRecord()} is the missing fact and it is derived rather than asserted: the
 * directory <em>observed</em> the refusal of a collection that {@link #assemble} reads strictly, so
 * "these rows will not open" follows from this read plus this service's own composition, and needs no
 * copy of another product's matrix to hold. See {@link Directory#blocksEveryRecord()}, and
 * {@code PatientResource.RESTRICTED_FOLLOW_UPS} for what reaches the wire.
 *
 * <p>What changes for everything still strict is the sentence: a refusal no longer opens with
 * <em>patientservice could not be read</em>, which was a claim about the sibling's health that the
 * sibling had just disproved by answering. See {@code PatientServiceUnavailableException.message}.
 *
 * <p><strong>And a degraded read leaves a trace that says <em>which</em> of the two it was</strong>
 * (backlog item 116). Both branches logged at {@code debug}, which production does not emit, so the
 * only production evidence that a directory had been served short of rows was an INFO line naming a
 * path — indistinguishable from the same line for a pharmacist losing a column. See
 * {@link PatientDirectoryRestrictionMeters}, which is driven from the restriction set this method
 * returns, and {@link RestrictedPart#removesRows()}, which is where the two are told apart.
 *
 * <p><strong>Degrading cannot widen anything, and that is why it is safe here.</strong> The case half
 * of the caseload union only ever <em>adds</em> patients, so losing it yields a strictly smaller
 * directory — the same direction as having no cases at all. It is not the same as reading a refusal as
 * an entitlement answer, which is what {@link #entitledCases} must never do and does not.
 */
@Service
public class PatientDirectoryService {

    /** WHO's definition, and the one the dashboard's "kids" tile has always meant. */
    private static final int CHILD_AGE_LIMIT = 18;

    private static final Logger log = LoggerFactory.getLogger(PatientDirectoryService.class);

    private final TaskRepository taskRepository;
    private final ProfileRepository profileRepository;
    private final PatientServiceClient patientService;
    private final PatientWriteReceiptRepository receiptRepository;
    private final PatientDirectoryRestrictionMeters restrictionMeters;

    public PatientDirectoryService(
        TaskRepository taskRepository,
        ProfileRepository profileRepository,
        PatientServiceClient patientService,
        PatientWriteReceiptRepository receiptRepository,
        PatientDirectoryRestrictionMeters restrictionMeters
    ) {
        this.taskRepository = taskRepository;
        this.profileRepository = profileRepository;
        this.patientService = patientService;
        this.receiptRepository = receiptRepository;
        this.restrictionMeters = restrictionMeters;
    }

    /**
     * The calling clinician's own professional profile id.
     *
     * <p>Empty when the caller has no profile — a freshly registered account before onboarding
     * completes. That is a legitimate state, and it yields an empty directory rather than an error:
     * such an account genuinely has no patients.
     */
    public Optional<String> callerProfileId() {
        return SecurityUtils.getCurrentAccountId().flatMap(profileRepository::findByAccountId).map(net.jojoaddison.domain.Profile::getId);
    }

    /**
     * Patient ids the clinician has worked with, from tasks here and cases in patientservice.
     *
     * <p>Half of this set comes from the sibling, so it cannot be computed at all while the sibling is
     * unreadable. It raises rather than returning the task half alone — a partial entitlement set is
     * an entitlement set that says no to real patients, which is item 24's defect in a smaller shape.
     *
     * <p><b>The estate-wide read here is the one backlog item 23 could not remove.</b> The question is
     * "which patients are this clinician's", and the sibling's only filter is a single
     * {@code patientId} — the one value this method is trying to discover. Nothing narrows a set-shaped
     * question at the source until an {@code assignedProfessionalId} filter or a clinician-scoped read
     * exists over there, which is a contract to agree with hc-patient's owners rather than something to
     * invent in a client. <b>Asking about <em>one</em> patient is a different question</b> and does not
     * come through here: see {@link #entitledCases}, which answers membership with a scoped read.
     *
     * @throws PatientServiceUnavailableException when the case collection could not be read
     */
    public Set<String> patientIdsFor(String professionalId) {
        Stream<String> fromTasks = taskRepository.findByAttendantId(professionalId).stream().map(Task::getPatientId);
        Stream<String> fromCases = patientService
            .clinicalCases()
            .stream()
            .filter(clinicalCase -> professionalId.equals(clinicalCase.assignedProfessionalId()))
            .map(ClinicalCase::patientId);
        // LinkedHashSet: a patient reached through both a task and a case must appear once, and the
        // directory should not reshuffle between requests for no reason.
        return Stream.concat(fromTasks, fromCases)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The task half of the union alone — this service's own answer to "whose patients are these".
     *
     * <p>Extracted from {@link #patientIdsFor} rather than copied, because
     * {@link #patientIdsWithinScope} is exactly "the union without the half the caller was refused"
     * and a second copy of this three-line stream is how the two would stop agreeing about blank ids.
     */
    private Set<String> patientIdsFromTasks(String professionalId) {
        return taskRepository
            .findByAttendantId(professionalId)
            .stream()
            .map(Task::getPatientId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * A composed part of the directory the caller's discipline may not read (backlog item 107).
     *
     * <p><b>The two differ in what their absence costs, and a client needs to know which.</b>
     * {@link #LAST_ACTIVITY} blanks a column on rows that are all present; {@link #CASE_ASSIGNMENTS}
     * means rows are <em>missing</em>, which nothing in the body can express — no per-row field can
     * describe a patient that is not there. That asymmetry is why the marker is collection-level.
     *
     * <p>{@link #token()} is what reaches the wire, so the enum can be renamed without breaking a
     * client and a client-facing name never has to be spelled twice.
     *
     * <p><b>These name what was refused <em>during this read</em>, not what the caller may never
     * see.</b> The difference is reachable and worth knowing before building anything on it:
     * {@link #withinScope} returns early when the caller has no patients at all, before the activity
     * log is ever asked for — so a technician with no tasks is answered {@code caseAssignments} alone,
     * while the same technician with one task is answered both, although their discipline is refused
     * the activity log in either case. The set therefore varies with caseload as well as with
     * discipline. It is the honest report of one read and it is deliberately not a capability list:
     * a client rendering a per-discipline badge from it would watch the badge change when a shift was
     * assigned. Asking hc-patient what a discipline may read is a different question, and nothing in
     * this stack can answer it — the scope-of-practice matrix lives over there.
     */
    public enum RestrictedPart {
        /**
         * patientservice's {@code /api/clinical-cases}. The case half of the caseload union is
         * unreadable, so a patient reached <em>only</em> through an assigned case is absent from the
         * directory. The task half is unaffected and is what is listed.
         */
        CASE_ASSIGNMENTS("caseAssignments", true, true),
        /**
         * patientservice's {@code /api/activity-logs}. Every patient is listed; {@code lastActivityAt}
         * is {@code null} on all of them and the default ordering falls back to that null, so the list
         * is not in recency order. Without this marker a client cannot tell that from a caseload
         * nobody has touched.
         */
        LAST_ACTIVITY("lastActivity", false, false);

        private final String token;
        private final boolean removesRows;
        private final boolean blocksRecord;

        RestrictedPart(String token, boolean removesRows, boolean blocksRecord) {
            this.token = token;
            this.removesRows = removesRows;
            this.blocksRecord = blocksRecord;
        }

        /** The stable name a client sees. */
        public String token() {
            return token;
        }

        /**
         * Whether losing this part takes <em>patients</em> out of the list rather than blanking a
         * column on the patients that remain (backlog item 116).
         *
         * <p><b>The asymmetry this enum's javadoc argues, made answerable rather than only stated.</b>
         * It was stated here and followed nowhere: both degrade branches logged at {@code debug},
         * which {@code application-prod.yml}'s {@code net.jojoaddison: INFO} does not emit, so the two
         * were indistinguishable in the one environment where the distinction matters.
         * {@link PatientDirectoryRestrictionMeters} keys its escalation on this rather than on a
         * constant of its own, so <b>a third part added later has to answer the question here</b>,
         * beside the description of what its absence costs, instead of inheriting an answer from a
         * file that never mentions it.
         *
         * <p>It is not a wire value and no client sees it: what reaches a client is {@link #token()},
         * and the difference between the two is expressed there by the marker being collection-level
         * at all — no per-row field can describe a patient who is not in the list.
         */
        public boolean removesRows() {
            return removesRows;
        }

        /**
         * Whether losing this part <em>also</em> makes {@code GET /api/patients/{id}} refuse, so that
         * every row of the directory it was withheld from is a row that will not open (backlog item
         * 128).
         *
         * <p><b>This is a fact about this service's own composition, and deliberately not about
         * hc-patient's matrix.</b> It reads: the record path makes this read and does not tolerate
         * losing it. {@link #CASE_ASSIGNMENTS} is the clinical-cases collection, which
         * {@link #assemble} asks {@link #entitledCases} for <em>first</em> and strictly, because that
         * is what decides whether the patient is the caller's at all — so a caller refused it reaches
         * no record, by item 111's Decision C and for its reason. {@link #LAST_ACTIVITY} is tolerated
         * on a record exactly as it is on the list ({@link #activityLogWithinScope}), so it costs a
         * panel and never the record.
         *
         * <p><b>{@code true} for {@link #CASE_ASSIGNMENTS} rests on an ordering that is argued where it
         * is written and would be easy to undo by accident.</b> {@link #entitledCases} makes the scoped
         * case read <em>before</em> {@link #scheduledWith} is consulted, deliberately — see its javadoc
         * — so even a row the directory listed on the strength of a task alone still refuses, because
         * the refusal arrives before the task half is reached. Reverse that order and a task-entitled
         * patient would open, this flag would be wrong for every such row, and the directory would be
         * telling clients the opposite of what the endpoint does. The test named below is what catches
         * it; this paragraph is what tells whoever reverses the order why the test went red.
         *
         * <p><b>What the directory may then say, and what it may not.</b> The honest sentence is the
         * past tense one: <em>a part was withheld from this read that the record endpoint requires</em>.
         * The prediction a client draws from it — <em>these rows will not open</em> — is the same
         * prediction {@link #token()} already licenses for a column, and it goes stale in the same way
         * and at the same rate as the directory beside it. <b>No discipline is named and none could
         * be</b>: the scope-of-practice matrix is hc-patient's, a copy of it here is the drift
         * {@link PatientDirectoryRestrictionMeters} exists to catch, and if that matrix moves the next
         * directory read observes the change and the marker disappears with it.
         *
         * <p><b>It is a sufficient signal, not a complete one, and that asymmetry is chosen.</b>
         * Present, it is never wrong; absent, the record may still refuse for a read the directory
         * never makes. The gap has exactly one shape: a caller who may read hc-patient's
         * {@code DIAGNOSIS} domain (which gates the cases collection) and not its {@code MEDICATION}
         * one (which gates the medication read {@link #assemble} also makes strictly). Such a caller
         * would open a clean, unmarked directory and get a 503 on every row.
         *
         * <p><b>Nobody is in that position, and this deliberately does not rely on that.</b> In
         * hc-patient's {@code ScopeOfPractice} the two read sets are identical — all eight disciplines
         * but the technician hold both — so today the marker is necessary <em>and</em> sufficient. That
         * is a coincidence of another product's table, not a property of anything here: encoding it, or
         * narrowing the record's strict reads because of it, would be the second copy of a matrix that
         * backlog item 116 exists to catch drifting. It is written down only so a reader knows how much
         * slack there is, and the slack is a fact about a file in another repository that may change
         * without this one hearing.
         *
         * <p>A missing marker degrades to the behaviour this item is about — the clinician learns by
         * tapping. A marker that was wrong when present would be a confident lie, which is the direction
         * backlog items 62, 64, 67, 73, 78 and 80 exist to refuse, so the implication is only ever
         * asserted in the direction that holds.
         *
         * <p>Answered here, beside {@link #removesRows()} and for its reason: a third part added later
         * has to state what its absence costs the record as well as what it costs the list, rather
         * than inheriting an answer from a constant in a file that never mentions it. {@code
         * PatientDirectoryServiceUnitTest} holds this flag against what the record path actually does,
         * per constant and exhaustively, so the two cannot come apart.
         */
        public boolean blocksRecord() {
            return blocksRecord;
        }
    }

    /**
     * One page of the directory, and whatever the caller's discipline was not allowed to see in it.
     *
     * <p><b>A second value beside the page rather than a wider {@code PatientListItem}</b>, for two
     * reasons that both had to hold. A restriction is a fact about the <em>read</em>, not about any
     * row — repeating it on every row would state a global fact n times and still could not state the
     * one that matters, that rows are missing. And {@code web/} and {@code mobile/} both consume the
     * body of {@code GET /api/patients} as a bare JSON array today; wrapping it in an envelope would
     * break both on the deploy that shipped it, and neither is this item's to change.
     *
     * @param restrictions empty for the five disciplines that may read every part, which is the
     *     ordinary case and the one nothing should pay for. <b>Iterates in {@link RestrictedPart}
     *     declaration order</b>, which the header rendered from it is a contract about — see the
     *     comment where it is built, and do not pass a general-purpose {@code Set} in here.
     */
    public record Directory(Page<PatientListItem> page, Set<RestrictedPart> restrictions) {
        /**
         * Whether opening <em>any</em> row of this page would be refused (backlog item 128).
         *
         * <p><b>Every row, not some.</b> What was refused is a collection, for this caller, by their
         * discipline — not a row, and not a patient. So the question the client needs answering is a
         * property of the read, which is why it is derived from {@link #restrictions()} here rather
         * than marked per row: item 111's Decision A's argument, in the one direction it did not
         * anticipate.
         *
         * <p>Derived rather than stored, and derived from {@link RestrictedPart#blocksRecord()} rather
         * than from a list of constants, so that a third part cannot join the enum and quietly answer
         * {@code false} here. It is {@code false} whenever nothing was restricted, which is almost
         * every request and the case that should pay nothing.
         */
        public boolean blocksEveryRecord() {
            return restrictions.stream().anyMatch(RestrictedPart::blocksRecord);
        }
    }

    /**
     * How a caller may narrow the directory. Every field is optional; all present fields must match.
     *
     * <p>{@code sex} and {@code childrenOnly} mirror the two filters the web dashboard already offers
     * (its {@code PatientDirectoryFilters}), so a clinician sees the same result set on either client.
     */
    public record DirectoryFilter(String query, String sex, Boolean childrenOnly) {
        public static final DirectoryFilter NONE = new DirectoryFilter(null, null, null);

        boolean matches(PatientListItem patient) {
            return matchesQuery(patient) && matchesSex(patient) && matchesChildren(patient);
        }

        private boolean matchesQuery(PatientListItem patient) {
            if (query == null || query.isBlank()) {
                return true;
            }
            String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
            // Name and id both, because a clinician reading from a wristband has the id and not the
            // spelling. Null name is a data fault in the sibling service, not a reason to throw.
            return (
                (patient.patientName() != null && patient.patientName().toLowerCase(java.util.Locale.ROOT).contains(needle)) ||
                (patient.id() != null && patient.id().toLowerCase(java.util.Locale.ROOT).contains(needle))
            );
        }

        private boolean matchesSex(PatientListItem patient) {
            return sex == null || sex.isBlank() || sex.trim().equalsIgnoreCase(patient.sex());
        }

        private boolean matchesChildren(PatientListItem patient) {
            return !Boolean.TRUE.equals(childrenOnly) || patient.isChild();
        }
    }

    /**
     * The sorts this endpoint accepts, and the only ones.
     *
     * <p>The list is assembled in memory from two services, so a sort is a comparator lookup rather
     * than a database index — an arbitrary {@code sort=} from a client would otherwise reach it.
     * Unknown properties are rejected rather than ignored: a silently-unsorted page looks like a
     * backend that lost the clinician's ordering preference, and nobody reports that as a bug.
     */
    private static final Map<String, Comparator<PatientListItem>> SORTABLE = Map.of(
        "patientName",
        Comparator.comparing(PatientListItem::patientName, Comparator.nullsLast(Comparator.naturalOrder())),
        "lastActivityAt",
        Comparator.comparing(PatientListItem::lastActivityAt, Comparator.nullsLast(Comparator.naturalOrder())),
        "sex",
        Comparator.comparing(PatientListItem::sex, Comparator.nullsLast(Comparator.naturalOrder())),
        "id",
        Comparator.comparing(PatientListItem::id, Comparator.nullsLast(Comparator.naturalOrder()))
    );

    /** Sortable property names, for an error message that tells the caller what to use instead. */
    public static Set<String> sortableProperties() {
        return new java.util.TreeSet<>(SORTABLE.keySet());
    }

    /**
     * One page of the clinician's directory.
     *
     * <p><b>Paged here, not in the datastore.</b> The set is the union of this service's tasks and
     * patientservice's cases, so there is no single collection to page and no single clock to sort
     * by — the whole union is assembled, then filtered, sorted and sliced. That is honest for a
     * caseload of tens and would not be for thousands; the ceiling is the sibling services' own
     * unpaged reads, not this method.
     *
     * <p>What it buys is real all the same: a phone on mobile data receives twenty rows instead of
     * the whole caseload, and {@code X-Total-Count} finally means the number of matches rather than
     * the number of rows in the body.
     *
     * <h3>The one read here that tolerates a refusal (backlog item 107)</h3>
     * <b>This is the clinician's first screen, and it is the method that must answer.</b> Three of the
     * eight disciplines are refused a part of what it composes, permanently and by hc-patient's design,
     * and until 2026-09-11 each of them got a 503 for the whole directory. So this method — alone among
     * the reads in this class — catches a refusal, leaves the part out, and <em>names</em> it in the
     * {@link Directory} it returns. It still raises for everything else: an outage, a schema drift, a
     * budget, a page guard and a missing token are all failures to read something the caller is
     * entitled to, and serving a partial directory for one of those is item 24's defect returning by
     * the door this change opened. The two are told apart by
     * {@link PatientServiceUnavailableException.Fault#isAuthorisationRefusal()} and by nothing else —
     * never by a status, a message or a path.
     *
     * <p><b>The profile read is deliberately not in the tolerant set.</b> {@code /api/profiles} is
     * where every row's substance comes from, so a caller refused it has no directory to be served a
     * degraded version of — the honest answer is the refusal, not an empty list with a note attached.
     * It is 200 for all eight disciplines today; the rule is stated because that is a fact about
     * hc-patient's matrix and not a property of this code.
     *
     * @return the page, and the parts of it the caller's discipline may not read
     */
    public Directory directory(Pageable pageable, DirectoryFilter filter) {
        DirectoryFilter effective = filter == null ? DirectoryFilter.NONE : filter;
        // EnumSet, and declared as one rather than as a Set, which is load-bearing twice over.
        //
        // Iteration is declaration order, which is what makes the header byte-stable. And the static
        // type selects EnumSet.copyOf(EnumSet) at the bottom of this method — the overload that cannot
        // throw. Its Collection sibling rejects an empty argument it cannot infer an element type
        // from, which is exactly the no-restrictions case, i.e. almost every request.
        EnumSet<RestrictedPart> restrictions = EnumSet.noneOf(RestrictedPart.class);
        List<PatientListItem> matches = withinScope(restrictions).stream().filter(effective::matches).toList();

        // Sorting is validated before anything is sliced, and it throws — an unsortable property is a
        // 400 whether or not a part was refused.
        List<PatientListItem> ordered = sort(matches, pageable.getSort());
        Page<PatientListItem> page;
        if (pageable.isUnpaged()) {
            page = new PageImpl<>(ordered, pageable, ordered.size());
        } else {
            int from = (int) Math.min(pageable.getOffset(), ordered.size());
            int to = Math.min(from + pageable.getPageSize(), ordered.size());
            page = new PageImpl<>(ordered.subList(from, to), pageable, ordered.size());
        }
        // NOT Set.copyOf, which was here until the review of this item and does NOT preserve order:
        // for two elements it yields an ImmutableCollections.Set12 whose iteration order is decided by
        // a per-JVM SALT. Measured on this project's JDK, ten JVM starts: six "caseAssignments,
        // lastActivity" and four the other way, while EnumSet.copyOf held in all ten. The value would
        // therefore have flipped between restarts of the api container and between replicas, under a
        // javadoc promising it was stable — and the only input that reaches it is a technician with at
        // least one task, who is refused both parts at once.
        //
        // Recorded here rather than in the two degrade branches (backlog item 116), for one reason
        // that is worth more than the saving: what is metered is then the same set that is rendered
        // into X-Restricted-Parts, and the two cannot come to disagree. It also counts directories
        // that were actually SERVED — an unsortable `sort=` throws above this line, and a request
        // answered 400 delivered no degraded directory to count.
        restrictionMeters.record(restrictions);
        return new Directory(page, Collections.unmodifiableSet(EnumSet.copyOf(restrictions)));
    }

    /**
     * The directory as far as the caller's discipline is permitted to see it, recording what it was not.
     *
     * <p>The mirror of {@link #caseload} — same composition, same order, same {@code isChild}
     * arithmetic — differing only in that the two reads whose absence the list can survive are allowed
     * to be refused. Both are expressed as a catch around the strict helper rather than as a second
     * code path, so a change to how the directory is assembled cannot apply to one and miss the other.
     */
    private List<PatientListItem> withinScope(Set<RestrictedPart> restrictions) {
        String professionalId = callerProfileId().orElse(null);
        if (professionalId == null) {
            return List.of();
        }
        Set<String> patientIds = patientIdsWithinScope(professionalId, restrictions);
        if (patientIds.isEmpty()) {
            return List.of();
        }
        return compose(patientIds, lastActivityWithinScope(restrictions));
    }

    /**
     * The caseload union, or the task half of it when the sibling refuses the caller the case half.
     *
     * <p><b>Refusing to compute this is what took the directory down</b> (backlog item 107): a
     * technician is refused {@code /api/clinical-cases} outright, so {@link #patientIdsFor} raised
     * before a single row was composed. The fallback is strictly narrower than the union — dropping the
     * case half can only remove patients — so it cannot be used to reach anybody, which is the whole
     * reason it is admissible here and would not be in {@link #entitledCases}.
     *
     * <p>An outage is rethrown untouched. {@link #patientIdsFor}'s own javadoc argues why a partial
     * entitlement set is not an entitlement set, and that argument is about a half that <em>exists and
     * could not be read</em>; it does not transfer to a half the caller may never read, where the
     * alternative is not "wait five minutes" but "no directory, ever".
     */
    private Set<String> patientIdsWithinScope(String professionalId, Set<RestrictedPart> restrictions) {
        try {
            return patientIdsFor(professionalId);
        } catch (PatientServiceUnavailableException e) {
            if (!e.fault().isAuthorisationRefusal()) {
                throw e;
            }
            // DEBUG, and it stays DEBUG: this line carries the upstream message and is for someone
            // already reproducing the problem. The production signal for this branch is
            // PatientDirectoryRestrictionMeters, driven from the restriction set once the directory is
            // built — see backlog item 116 for why it is not a per-request WARN here.
            log.debug("Composing the directory without the case half: {}", e.getMessage());
            restrictions.add(RestrictedPart.CASE_ASSIGNMENTS);
            return patientIdsFromTasks(professionalId);
        }
    }

    /** The recency ordering, or none of it when the caller may not read the activity log. See above. */
    private Map<String, String> lastActivityWithinScope(Set<RestrictedPart> restrictions) {
        try {
            return lastActivityByPatient();
        } catch (PatientServiceUnavailableException e) {
            if (!e.fault().isAuthorisationRefusal()) {
                throw e;
            }
            // As above. This part is counted by PatientDirectoryRestrictionMeters and deliberately not
            // escalated beyond that: it blanks a column on rows that are all present, which is the
            // asymmetry RestrictedPart.removesRows() states (backlog item 116).
            log.debug("Composing the directory without last-activity: {}", e.getMessage());
            restrictions.add(RestrictedPart.LAST_ACTIVITY);
            // Empty, and the marker above is what stops it being read as "nobody has been seen".
            return Map.of();
        }
    }

    /** Applies a whitelisted sort, or leaves the default newest-activity-first order in place. */
    private List<PatientListItem> sort(List<PatientListItem> patients, Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return patients;
        }
        Comparator<PatientListItem> comparator = null;
        for (Sort.Order order : sort) {
            Comparator<PatientListItem> next = SORTABLE.get(order.getProperty());
            if (next == null) {
                throw new IllegalArgumentException(
                    "Cannot sort patients by '" + order.getProperty() + "'; sortable properties are " + sortableProperties()
                );
            }
            if (order.isDescending()) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return patients.stream().sorted(comparator).toList();
    }

    /**
     * The caseload rows {@link #summary()} counts — <b>every read that a count depends on is strict, and
     * the one that no count depends on is not</b> (backlog item 112).
     *
     * <p><b>This is an argument against item 111's Decision C, made narrowly and on a measurement.</b>
     * Decision C left {@code summary()} refusing because "every {@code DashboardSummary} field is a count
     * and there is nowhere to say a count is partial, so degrading would turn <em>could not be counted</em>
     * into a confident wrong number". That reasoning is correct and this method keeps it — for the case
     * half. It does not hold for the activity log, and the difference is not a matter of taste:
     *
     * <ul>
     *   <li><b>{@code /api/clinical-cases} is half of the caseload union.</b> Losing it removes patients
     *       from {@link #patientIdsFor}'s set, so {@code patients}, {@code female}, {@code male} and
     *       {@code kids} all come out smaller than the truth, and the response has no way to say so. Every
     *       word of Decision C applies. It stays strict, and a technician still gets a 503.
     *   <li><b>{@code /api/activity-logs} contributes to no field of {@code DashboardSummary} at all.</b>
     *       Follow it through: {@link #lastActivityByPatient} feeds {@link #compose}'s second argument,
     *       which reaches {@link #toListItem}'s {@code lastActivityAt} and the sort beside it — and
     *       {@code summary()} reads {@code size()}, {@code sex()} and {@code isChild()} and never touches
     *       either. A pharmacist's four counts are <b>byte-identical</b> with the activity log read and
     *       without it. There is no partial number to mark, because there is no affected number.
     * </ul>
     *
     * <p>So a pharmacist and a chemist were being handed a 503 for a dashboard whose every figure this
     * service could compute exactly, over a collection none of those figures is made of. That is not
     * Decision C being applied; it is Decision C's <em>rule</em> being applied to a read its
     * <em>reason</em> does not cover — and the cost was the dashboard, for two of the eight disciplines,
     * permanently.
     *
     * <p><b>And nothing is marked on the response, which is the other half of the same argument.</b>
     * {@code GET /api/dashboard/summary} emits no {@code X-Restricted-Parts}. Decision A says a refused
     * part is named rather than silently emptied, and nothing here is emptied: the answer is complete.
     * Naming a withheld part beside four correct counts would make a client say <em>some of this is not
     * permitted</em> about figures that are whole — a new false sentence, pointing the other way, which is
     * the failure mode this backlog has recorded three times inside its own fixes. The honest report of
     * this read is the counts.
     *
     * <p><b>Rejected: route {@code summary()} through {@link #directory(Pageable, DirectoryFilter)} and
     * inspect its restrictions.</b> It reads elegantly and it is wrong twice. It would record a directory
     * counter for a dashboard read (backlog item 116's meter has one call site on purpose), and where the
     * case half was refused it would have to <em>synthesise</em> a refusal to throw, having already
     * swallowed the real one — a message nothing established, in place of the sibling's own.
     */
    private List<PatientListItem> countableCaseload() {
        return caseload(this::lastActivityIfPermitted);
    }

    /**
     * The caseload rows, assembled once, with only the recency read varying between the callers.
     *
     * <p>Extracted rather than copied so that its callers cannot drift in <em>what</em> they assemble
     * while differing in what they will lose — the argument {@link #compose} and {@link #withinScope}
     * already make one layer down. The supplier is invoked at the same point the expression it replaced
     * was evaluated, so a caller with no profile and a caller with no patients still make no upstream
     * recency read at all.
     *
     * <p><b>There was a public {@code directory()} here — the fully strict composition — and backlog item
     * 112's review removed it rather than leaving it</b>. It had exactly one caller, {@link #summary},
     * and this item moved that caller to {@link #countableCaseload()}; what was left was a public method
     * nothing on a running stack reached, exercised only by its own tests, which go on passing while the
     * thing they describe stops being true. Keeping it would also have contradicted this item's own
     * reasoning one method away: {@link #record} keeps its strict form <em>because</em> it has a caller
     * that needs it, and a strict sibling kept on any weaker ground than that is the exemption this
     * change refused to grant anywhere else. Its tests now go through
     * {@link #directory(Pageable, DirectoryFilter)} and {@link #summary}, which is what production does.
     */
    private List<PatientListItem> caseload(Supplier<Map<String, String>> lastActivity) {
        String professionalId = callerProfileId().orElse(null);
        if (professionalId == null) {
            return List.of();
        }
        Set<String> patientIds = patientIdsFor(professionalId);
        if (patientIds.isEmpty()) {
            return List.of();
        }
        return compose(patientIds, lastActivity.get());
    }

    /**
     * The recency map, or an empty one when the caller's discipline may not read the activity log.
     *
     * <p>Only a refusal is tolerated; everything else raises, for {@link #lastActivityWithinScope}'s
     * reason. The map is discarded by {@link #summary()}'s arithmetic either way — see
     * {@link #countableCaseload()} for why that is what makes tolerating it honest here and not in
     * {@link #withinScope}, whose rows carry the field this populates.
     */
    private Map<String, String> lastActivityIfPermitted() {
        try {
            return lastActivityByPatient();
        } catch (PatientServiceUnavailableException e) {
            if (!e.fault().isAuthorisationRefusal()) {
                throw e;
            }
            log.debug("Counting the dashboard summary without last-activity, which no count uses: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Profiles plus recency into list rows, shared by the strict and the scoped forms.
     *
     * <p>One body rather than two so that the two directories cannot drift in what they show; they
     * differ only in which reads they are willing to lose, which is decided before this is called.
     */
    private List<PatientListItem> compose(Set<String> patientIds, Map<String, String> lastActivity) {
        return profilesByPatientId(patientIds)
            .values()
            .stream()
            .map(profile -> toListItem(profile, lastActivity.get(profile.patientId())))
            .sorted(Comparator.comparing(PatientListItem::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /**
     * One patient's full record, or empty when the caller has not worked with them.
     *
     * <p>Empty covers both "no such patient" and "not yours" on purpose: distinguishing them tells a
     * caller whether a patient id exists, which is not something an unrelated clinician should be
     * able to probe.
     *
     * <p><b>It does not cover "patientservice is down", and that is backlog item 24.</b> Both
     * {@code entitledCases} and {@code patientProfile} below read across the wire; a read that
     * failed raises out of this method rather than becoming an empty {@link Optional}, so the caller
     * sees a 503 instead of being told the patient in front of them is not theirs.
     *
     * <p><b>Six estate-wide reads became five scoped ones, and the sixth is gone</b> (backlog item 23).
     * Every collection here is about one patient, so each is asked for with {@code patientId} rather
     * than pulled whole and filtered — and the case collection, which used to be read twice in this
     * one method (once for the entitlement check and once for the list), is now read once and used
     * twice. The saving is not a nicety: after item 22 made these reads complete, each estate-wide one
     * was seven requests and ~1260 rows.
     *
     * <p><b>This is the strict form, and it is no longer what the endpoint serves</b> (backlog item
     * 112). Every read it makes must succeed, a refusal raising exactly as an outage does. {@code GET
     * /api/patients/{id}} goes through {@link #recordWithinScope}, which loses the activity log rather
     * than the record and says which it was.
     *
     * <p><b>Its one caller is {@link #activityById}, and the claim that it has two was wrong</b> — an
     * earlier draft of this paragraph named {@link #reportById} as well, on the reasoning that a replayed
     * write must answer with the filed entry rather than a fresh copy. That is true of both and it does
     * not make this the right read for both: a report is not made of the activity log, so routing it
     * through here answered 503 for a pharmacist replaying a report they had successfully filed and could
     * read perfectly well. <b>The javadoc asserting a property the code did not have is what surfaced
     * it</b>, and the sentence and the defect were fixed together rather than the sentence alone — see
     * {@link #reportById}, which now composes from the report collection.
     *
     * @throws PatientServiceUnavailableException when the sibling could not be read
     */
    public Optional<PatientRecord> record(String patientId) {
        return assemble(patientId, this::activityLog);
    }

    /**
     * One patient's record, and whatever the caller's discipline was not allowed to read in it — the
     * form the endpoint serves (backlog item 112).
     *
     * <p><b>Item 107 gave three disciplines a directory in which every row was a dead end.</b> A
     * pharmacist and a chemist could open the patient list and not one patient in it: {@link #record}
     * reads {@code /api/activity-logs} unconditionally, hc-patient refuses them that collection, and the
     * whole record came back 503 over one panel of it. This method is the same composition with that one
     * read allowed to be refused and <em>named</em>, exactly as
     * {@link #directory(Pageable, DirectoryFilter)} is to a strict composition.
     *
     * <h3>One read tolerated, and the rest are not — the measurements this rests on</h3>
     * Taken through the gateway on the quality stack, 2026-09-14, all eight disciplines, on each
     * collection {@link #record} composes:
     *
     * <table border="1">
     *   <caption>hc-patient's answer per discipline</caption>
     *   <tr><th></th><th>/api/profiles</th><th>/api/clinical-cases</th><th>/api/activity-logs</th>
     *       <th>/api/medications</th><th>/api/reports</th></tr>
     *   <tr><td>doctor, nurse, paramedic, therapist, carer</td>
     *       <td>200</td><td>200</td><td>200</td><td>200</td><td>200</td></tr>
     *   <tr><td><b>pharmacist, chemist</b></td>
     *       <td>200</td><td>200</td><td><b>403</b></td><td>200</td><td>200</td></tr>
     *   <tr><td><b>technician</b></td>
     *       <td>200</td><td><b>403</b></td><td><b>403</b></td><td><b>403</b></td><td><b>403</b></td></tr>
     * </table>
     *
     * <p><b>So the two refused disciplines are not one case, and answering them the same way would be
     * wrong for one of them.</b> A pharmacist loses a <em>field</em> — the activity list, and with it the
     * {@code lastActivityAt} derived from its first entry — out of a record whose cases, medications,
     * reports and demographics all still arrive. A technician loses the record: hc-patient admits them to
     * no clinical domain at all, and what would be left after withholding four collections is a name, a
     * date of birth and a telephone number, with four empty lists beside it. That is not a patient record
     * served short of one panel; it is a contact card wearing a patient record's shape, and serving it
     * {@code 200} would put "no medications" and "no cases" on a clinical screen — a far worse sentence
     * than the blank recency column item 107 was about, and the direction backlog items 62, 64, 67, 73,
     * 78 and 80 all exist to refuse.
     *
     * <p><b>Nothing new decides that, which is the point.</b> Item 111's Decision C already says only a
     * read that <em>supplies</em> may degrade and a read that <em>decides</em> may not, and it separates
     * the two disciplines here by itself: for a technician the refused collection is the one
     * {@link #entitledCases} uses to decide whether this patient is theirs at all, so the refusal reaches
     * a deciding read and raises. No per-discipline list is written down anywhere, and none could be —
     * the matrix is hc-patient's, and a copy of it here is the drift
     * {@link PatientDirectoryRestrictionMeters} exists to catch.
     *
     * <h3>Rejected: degrade the entitlement read to the task half as the directory does</h3>
     * {@link #patientIdsWithinScope} falls back to tasks alone on a refusal, and the same fallback here
     * would let a technician open the patients their degraded directory already lists. It was rejected
     * twice over. The record it produced would be the hollow one above; and where the task half says
     * <em>no</em>, the fallback would answer {@code 404 "no such patient for this clinician"} from a
     * collection that was never read — telling a technician standing next to their own patient that the
     * patient is not theirs, which is backlog item 24's defect exactly, arriving by the door this change
     * would have opened. A directory may be narrowed by a read that did not happen; an entitlement may
     * not be denied by one.
     *
     * <h3>Rejected: omit {@code activities} rather than empty it</h3>
     * A {@code null} list would be unmistakable on the wire, and it would break {@code web/} and
     * {@code mobile/} on the release that shipped it — both iterate the field. That is item 111 Decision
     * A's argument for a header over a body change, and it holds here unchanged.
     *
     * @return empty when the patient is not the caller's, exactly as {@link #record} does; otherwise the
     *     record and the parts of it the caller's discipline may not read
     */
    public Optional<RecordView> recordWithinScope(String patientId) {
        // EnumSet, declared as one, for the two reasons given where Directory is built: iteration is
        // declaration order, and the static type selects the copyOf overload that cannot throw on empty.
        EnumSet<RestrictedPart> restrictions = EnumSet.noneOf(RestrictedPart.class);
        return assemble(patientId, id -> activityLogWithinScope(id, restrictions)).map(
            found -> new RecordView(found, Collections.unmodifiableSet(EnumSet.copyOf(restrictions)))
        );
    }

    /**
     * One patient's record, and whatever was withheld from it.
     *
     * <p>The record's counterpart to {@link Directory}, and a second value beside the body for the same
     * reason: {@code web/} and {@code mobile/} consume {@code GET /api/patients/{id}} as the record
     * object itself, so a restriction has to travel beside it rather than inside it.
     *
     * @param restrictions empty for the six disciplines that may read every part of a record, which is
     *     the ordinary case. Iterates in {@link RestrictedPart} declaration order — do not pass a
     *     general-purpose {@code Set} in here.
     */
    public record RecordView(PatientRecord record, Set<RestrictedPart> restrictions) {}

    /**
     * The record itself, with the one read that may be refused supplied by the caller.
     *
     * <p>One body rather than two, for {@link #compose}'s reason: the strict and the scoped forms must
     * not drift in what a record contains, and they differ only in whether the activity log may be lost.
     * Passing the read in rather than a flag keeps the decision at the call site, where the two public
     * methods above each state it once.
     */
    private Optional<PatientRecord> assemble(String patientId, Function<String, List<ActivityLogEntry>> activityLog) {
        String professionalId = callerProfileId().orElse(null);
        // The same cases answer both questions: whether this patient is the caller's at all, and what
        // their case list is. Reading them twice was the cheapest half of backlog item 23.
        Optional<List<ClinicalCase>> entitled = entitledCases(professionalId, patientId);
        if (entitled.isEmpty()) {
            // Reached only when the caseload was actually read. An unreadable one raised above.
            return Optional.empty();
        }
        PatientProfile profile = patientProfile(patientId).orElse(null);
        if (profile == null) {
            return Optional.empty();
        }

        // Already this patient's — entitledCases narrows locally, for the reason given there — so no
        // second filter is needed here and none is written, unlike the collections below whose rows
        // this service has not looked at.
        List<CaseSummary> cases = entitled
            .orElseThrow()
            .stream()
            .map(
                c ->
                    new CaseSummary(
                        c.id(),
                        c.patientId(),
                        text(c.openedAt()),
                        c.brief(),
                        c.status() == null ? null : c.status().toLowerCase(java.util.Locale.ROOT)
                    )
            )
            .toList();

        List<ActivityLogEntry> activities = activityLog.apply(patientId);

        List<RecordEntry> medications = patientService
            .medications(patientId)
            .stream()
            .filter(m -> patientId.equals(m.patientId()))
            .map(m -> new RecordEntry(m.id(), occurredAt(null, m.startedOn() == null ? m.createdDate() : m.startedOn()), m.name()))
            .toList();

        List<ClinicalReport> reports = clinicalReports(patientId);

        PatientListItem summary = toListItem(profile, activities.isEmpty() ? null : activities.get(0).occurredAt());
        return Optional.of(
            new PatientRecord(
                summary.id(),
                summary.patientName(),
                summary.lastActivityAt(),
                summary.sex(),
                summary.isChild(),
                text(profile.birthDate()),
                profile.contactPhone(),
                profile.email(),
                emergencyContact(profile),
                null,
                cases,
                // Visitations have no source: patientservice serves no visitation collection, and
                // this service holds none. Empty rather than invented — see PatientDtos.
                List.of(),
                activities,
                medications,
                reports
            )
        );
    }

    /**
     * One patient's activity log, mapped into the record's shape — <b>the strict form</b>.
     *
     * <p>occurredAt is the clinical time — when the thing happened — falling back to the filing date.
     * Ordering a record by when it was typed rather than when it happened puts a late-entered
     * observation at the top, which reads as the most recent event.
     *
     * <p>The in-memory {@code patientId} filter is kept although the sibling now scopes the query: it
     * costs nothing over one patient's rows and it is what makes this answer independent of the far side
     * honouring a parameter. It is no longer the mechanism.
     */
    private List<ActivityLogEntry> activityLog(String patientId) {
        return patientService
            .activityLogs(patientId)
            .stream()
            .filter(a -> patientId.equals(a.patientId()))
            .map(
                a ->
                    new ActivityLogEntry(
                        a.id(),
                        occurredAt(a.loggedAt(), a.createdDate()),
                        a.summary(),
                        a.summary(),
                        a.detail(),
                        text(a.createdDate())
                    )
            )
            .toList();
    }

    /**
     * The activity log, or none of it when the caller's discipline may not read it (backlog item 112).
     *
     * <p>The record's counterpart to {@link #lastActivityWithinScope}, and the same rule: a refusal is
     * tolerated and marked, and <b>everything else still raises</b>. An outage, a schema drift, a budget
     * and a page guard are all failures to read something the caller is entitled to, and a record served
     * without its activity list for one of those would be item 24's defect wearing this change's hat.
     * The two are told apart by {@link PatientServiceUnavailableException.Fault#isAuthorisationRefusal()}
     * and by nothing else.
     *
     * <p>It empties {@code lastActivityAt} as well as {@code activities}, because that field is the first
     * entry's timestamp — which is exactly what {@link RestrictedPart#LAST_ACTIVITY} already describes
     * for the directory, and why this needs no token of its own.
     */
    private List<ActivityLogEntry> activityLogWithinScope(String patientId, Set<RestrictedPart> restrictions) {
        try {
            return activityLog(patientId);
        } catch (PatientServiceUnavailableException e) {
            if (!e.fault().isAuthorisationRefusal()) {
                throw e;
            }
            // DEBUG for the reason the directory's two branches are: this line carries the upstream
            // message and is for someone already reproducing the problem.
            //
            // NOT metered, deliberately (backlog item 116). patient.directory.restricted is named and
            // described for the directory, and item 116 put its one call site where the Directory is
            // built precisely so the counter and X-Restricted-Parts could not disagree. A second call
            // site here would count a record read under a directory meter and move every number on a
            // panel without moving the thing the panel is about. The drift it watches for — a
            // discipline refused a part hc-patient's matrix is supposed to admit — reaches the
            // directory as well, on the screen every record read is opened from.
            log.debug("Composing the patient record without the activity log: {}", e.getMessage());
            restrictions.add(RestrictedPart.LAST_ACTIVITY);
            // Empty, and the marker above is what stops it being read as "nothing has happened".
            return List.of();
        }
    }

    /**
     * Files an activity-log entry against one of the caller's own patients.
     *
     * <p><b>The entitlement rule is the same union that governs reads.</b> A clinician who may not
     * read a patient may not write to one either, and routing the check through
     * {@link #patientIdsFor(String)} means the two can never drift apart — which they would if the
     * write path grew its own copy.
     *
     * @throws PatientNotInCaseloadException when the patient is not the caller's, or there is no
     *     caller profile. Deliberately the same answer as "no such patient": a clinician must not be
     *     able to discover that a patient id is real by trying to write to it.
     */
    public ActivityLogEntry appendActivity(String patientId, CreateActivity request) {
        String accountId = requireEntitlement(patientId).accountId();

        Optional<PatientWriteReceipt> replay = replayOf(request.clientRef(), accountId, patientId, "activity");
        if (replay.isPresent()) {
            return activityById(patientId, replay.orElseThrow().getCreatedId());
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("patientId", patientId);
        body.put("summary", request.title());
        body.put("detail", request.description());
        // loggedAt is the clinical time. Absent means now — a bedside entry describes the present,
        // and patientservice stamps the audit fields from the token regardless of what is sent.
        body.put("loggedAt", parseInstant(request.occurredAt()).orElseGet(Instant::now).toString());

        ActivityLog created = patientService.createActivityLog(body);
        recordReceipt(request.clientRef(), accountId, patientId, "activity", created == null ? null : created.id());
        return created == null
            ? null
            : new ActivityLogEntry(
                created.id(),
                occurredAt(created.loggedAt(), created.createdDate()),
                created.summary(),
                created.summary(),
                created.detail(),
                text(created.createdDate())
            );
    }

    /** Files a clinical report against one of the caller's own patients. See {@link #appendActivity}. */
    public ClinicalReport appendReport(String patientId, CreateReport request) {
        String accountId = requireEntitlement(patientId).accountId();

        Optional<PatientWriteReceipt> replay = replayOf(request.clientRef(), accountId, patientId, "report");
        if (replay.isPresent()) {
            return reportById(patientId, replay.orElseThrow().getCreatedId());
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("patientId", patientId);
        body.put("name", request.name());
        body.put("category", request.reportType());
        body.put("description", request.description());
        body.put("url", request.url());

        Report created = patientService.createReport(body);
        recordReceipt(request.clientRef(), accountId, patientId, "report", created == null ? null : created.id());
        return created == null
            ? null
            : new ClinicalReport(
                created.id(),
                occurredAt(null, created.reportDate() == null ? created.createdDate() : created.reportDate()),
                created.name(),
                created.category(),
                created.url()
            );
    }

    /**
     * The caller's own caseload, newest first — the case queue's source.
     *
     * <p>Assembled here rather than read from patientservice directly, and that is the whole point
     * of this method. The sibling's {@code /api/clinical-cases} takes a {@code patientId} and
     * <b>no clinician scope</b>: a client that calls it gets <em>every case in the estate</em> and
     * narrows the list in the browser. Going through here means the narrowing is server-side and the
     * caller never receives a case that is not theirs.
     *
     * <p><b>This read stays estate-wide, and it is the expensive one</b> (backlog item 23). The
     * question is which cases name this clinician, and the one filter on offer names a patient — so
     * there is nothing to pass. Narrowing it needs an {@code assignedProfessionalId} filter or a
     * clinician-scoped read agreed with hc-patient's owners; the per-patient methods below no longer
     * pay this cost, but this one and {@link #directory(Pageable, DirectoryFilter)} still do.
     */
    public Page<CaseSummary> myCases(Pageable pageable, String status) {
        String professionalId = callerProfileId().orElse(null);
        if (professionalId == null) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        List<CaseSummary> matches = patientService
            .clinicalCases()
            .stream()
            .filter(c -> professionalId.equals(c.assignedProfessionalId()))
            .filter(c -> c.archivedAt() == null)
            .filter(c -> status == null || status.isBlank() || status.equalsIgnoreCase(c.status()))
            .sorted(Comparator.comparing(ClinicalCase::openedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(this::toCaseSummary)
            .toList();
        return slice(matches, pageable);
    }

    /**
     * One patient's cases, entitlement-checked the same way their record is.
     *
     * <p>One scoped read serves both, because the entitlement check and the answer are the same rows:
     * see {@link #requireEntitlement}.
     *
     * <h3>It refuses a discipline hc-patient refuses the case collection, and that is the answer
     * (backlog item 127)</h3>
     * <b>This was left open by item 112 and decided here rather than inherited from it.</b> A technician
     * gets 503 from {@code GET /api/patients/&#123;id&#125;/cases} — measured through the gateway on the
     * quality stack, 2026-09-15, beside a nurse's 200 on the same patient — and item 112 deliberately did
     * not pre-judge whether that should change when it made {@link #recordWithinScope} tolerant. It should
     * not, and for a reason that is <em>stronger</em> here than on the record rather than merely inherited:
     *
     * <ul>
     *   <li><b>There is nothing left to serve.</b> {@link #recordWithinScope} degrades because a record
     *       refused its activity log still has cases, medications, reports and demographics — four
     *       collections that arrived. This response is the refused collection and nothing else. What a
     *       degradation could return is an empty page, and an empty page here reads <em>this patient has
     *       no cases</em>: a clinical statement, made to a clinician, that is false. That is the direction
     *       backlog items 62, 64, 67, 73, 78 and 80 exist to refuse, and it is worse than the
     *       <em>"no medications"</em> sentence item 112 already declined to print on a hollow record.
     *   <li><b>The same read decides the entitlement.</b> {@link #requireEntitlement} is
     *       {@link #entitledCases}, so item 111's Decision C applies unchanged: a read that
     *       <em>decides</em> may not degrade. The only fallback available is the task half, which
     *       {@link #recordWithinScope} rejected twice over — it would answer <em>no such patient for this
     *       clinician</em> from a collection nobody read wherever the task half says no, which is backlog
     *       item 24's defect, and would let the other rows through to the empty page above.
     *   <li><b>No header could carry it.</b> {@code X-Restricted-Parts} names a part withheld from a
     *       response that was still served. A 200, an empty array and a marker is the shape of
     *       <em>this collection is empty and by the way it was refused</em> — which is the conflation item
     *       107 removed from the directory, reintroduced on the one response with no remainder to attach
     *       it to.
     * </ul>
     *
     * <p><b>So the refusal stays and the sentence was what needed fixing</b>; see
     * {@link PatientServiceUnavailableException#title()}, where item 127's change actually landed. A
     * <em>client</em> is told before a clinician gets here, by {@code X-Restricted-Follow-Ups} on the
     * directory (backlog item 128) — and that header deliberately does <b>not</b> name this endpoint, for
     * the reason given on {@code PatientResource.RESTRICTED_FOLLOW_UPS}.
     *
     * <p><b>Told, not warned, and the gap between the two is backlog item 132, which is open.</b> Neither
     * {@code web/} nor {@code mobile/} reads that header yet, so no clinician is warned of anything today —
     * they still learn by tapping. Item 132 exists to name this chain's recurring failure, <em>a
     * capability is not an outcome</em>, which has happened three times here already; writing the
     * capability down as though it were the outcome is exactly how somebody closes 132 in two months as
     * already covered. The refusal below is right either way. What 132 decides is whether anybody finds
     * out before they tap.
     *
     * <p><b>What this decision is not.</b> It is not backlog item 113: whether a permanent refusal should
     * be 403 rather than 503 is a cross-repo behavioural change — {@code mobile/}'s offline queue splits on
     * 4xx versus 5xx — and it is orthogonal to whether this endpoint answers at all. And no discipline is
     * written down here or anywhere it is derived from; the scope-of-practice matrix is hc-patient's, and
     * the day it admits a technician to {@code DIAGNOSIS} this endpoint starts answering them with no
     * change here.
     */
    public Page<CaseSummary> casesFor(String patientId, Pageable pageable) {
        List<CaseSummary> matches = requireEntitlement(patientId)
            .cases()
            .stream()
            .filter(c -> patientId.equals(c.patientId()))
            .filter(c -> c.archivedAt() == null)
            .sorted(Comparator.comparing(ClinicalCase::openedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(this::toCaseSummary)
            .toList();
        return slice(matches, pageable);
    }

    /**
     * Updates the clinical fields of one of the caller's own cases.
     *
     * <p><b>This exists so the caller is scoped, not because the sibling is open.</b> patientservice's
     * {@code /api/clinical-cases} has no clinician scope, so a client calling it directly receives
     * every case in the estate and narrows the list itself. Routed through here it is behind
     * {@code CLINICAL_MUTATION} <em>and</em> the caseload check.
     *
     * <p>An earlier version of this note claimed the sibling's {@code requireWrite} passed for any
     * authenticated non-patient caller, so a read-only role could edit a diagnosis by going around.
     * <b>That was wrong and is retracted (2026-08-23):</b> a carer PATCHing a diagnosis there gets
     * 403, because their {@code ScopeOfPractice} grants OBSERVATION, CARE_PLAN and ENCOUNTER and not
     * DIAGNOSIS. The probe behind the original claim sent a merge-patch body without the {@code id}
     * that endpoint requires, so both roles got a 400 from validation before authorisation ran.
     *
     * <p>Only the four fields a clinician edits are forwarded. A whole-document PATCH would let a
     * caller move a case to another patient or reassign it, neither of which is this screen's job.
     */
    public CaseSummary updateCase(String patientId, String caseId, CaseUpdate changes) {
        // "No such case" only when the patient's cases were read and did not contain it. An unreadable
        // collection raises inside requireEntitlement before this stream begins — item 24.
        ClinicalCase existing = requireEntitlement(patientId)
            .cases()
            .stream()
            .filter(c -> caseId.equals(c.id()) && patientId.equals(c.patientId()))
            .findFirst()
            .orElseThrow(() -> new PatientNotInCaseloadException(patientId));

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        // patientservice's PATCH requires the body id to match the path id.
        body.put("id", existing.id());
        if (changes.symptoms() != null) {
            body.put("symptoms", changes.symptoms());
        }
        if (changes.diagnosis() != null) {
            body.put("diagnosis", changes.diagnosis());
        }
        if (changes.brief() != null) {
            body.put("brief", changes.brief());
        }
        if (changes.status() != null) {
            body.put("status", changes.status());
        }

        return toCaseSummary(patientService.patchClinicalCase(caseId, body));
    }

    /**
     * One case in full, for the detail screen — <b>archived cases included</b>.
     *
     * <p>Entitlement-checked against the patient, not the case: holding a case id is not authority
     * over it, which is the same reason the PATCH path carries the patient too.
     *
     * <h3>Why this one read asks for archived rows (backlog item 82)</h3>
     * Archived cases are excluded from every <em>list</em> here and that is right: retiring a case is
     * precisely the act of taking it out of the working queue. It was never right for reading one back,
     * and this method promised from the day it was written (2026-08-22) that <em>"a case archived while
     * a clinician had it open should render rather than 404 — they are being shown something that
     * exists, and the alternative reads as data loss"</em>. <b>The promise was never kept</b>: the
     * sibling's {@code includeArchived} defaults to false, this service did not override it, and so the
     * row never arrived. Item 23 corrected the comment to describe the code; this change does the
     * opposite, and the reasons it is the promise that survives rather than the comment are three.
     *
     * <p><b>hc-patient, which owns archiving, decided this on its own endpoint.</b> Its
     * {@code ClinicalCaseResource} says in place: <em>"They are excluded from the list, not hidden:
     * GET /&#123;id&#125; still returns an archived case, so a link or a bookmark to one keeps working
     * and nothing has to be un-archived merely to be read."</em> Every client in the estate that reads
     * a case by id gets one. This service reads cases as a list and so was the only place where
     * archiving behaved like deletion.
     *
     * <p><b>Archiving is a clinical act, not a records one.</b> patientservice gates {@code /archive}
     * on doctor and excludes {@code ROLE_ADMIN} deliberately — the inverse of the usual rule here. A
     * permission shaped like that says the act means "this episode of care is finished", not "withdraw
     * this data"; patient data is never deleted in this estate, and a read that 404s is the one
     * behaviour indistinguishable from deletion by the person looking at it. The clinician who retires
     * a case is the one role permitted to, and was the one most likely to lose it.
     *
     * <p><b>And the clinician had it on screen.</b> {@code mobile/}'s queue deliberately leaves an
     * archived row in place until the next refresh, so tapping it in that window is ordinary use; it
     * answered "this case could not be opened".
     *
     * <h3>Exactly what is now readable that was not — three grants, not one</h3>
     * An earlier draft of this paragraph said "one entitlement is added and it is deliberately the
     * narrowest one". <b>That understated it, and on a permission change the javadoc is the record.</b>
     * The caseload rule is unchanged in its own terms, but it is now evaluated against a list that
     * <em>contains archived rows</em>, so what a caller who passes it can reach has grown too:
     *
     * <ul>
     *   <li><b>G1 — entitled by a live assigned case:</b> a clinician with a live case of their own on
     *       this patient can now read <em>every archived case of that patient</em>, including ones
     *       assigned to somebody else.
     *   <li><b>G2 — entitled by a task:</b> the same, for a clinician whose tie to the patient is a
     *       scheduled visit rather than a case. Concretely: a nurse with a current visit on this
     *       patient can open a case a different doctor archived months ago and read its title, brief,
     *       symptoms and diagnosis. Before this change that was a 404.
     *   <li><b>G3 — the case names the caller:</b> a case whose {@code assignedProfessionalId} is the
     *       caller's may be read back by them, live or archived, with no other tie to the patient.
     * </ul>
     *
     * <p><b>G1 and G2 are not incidental and the promise cannot be kept without them.</b>
     * patientservice gates {@code /archive} on doctor alone, so the clinician who retires a case is
     * frequently <em>not</em> its assignee — under G3 by itself the motivating scenario would still
     * 404 for the very person who archived it. They are also hc-patient's own posture: its
     * {@code GET /clinical-cases/&#123;id&#125;} serves an archived case to anyone its
     * {@code patientScope} admits, and this service's caseload rule is the narrower of the two.
     *
     * <h3>What it still does not widen</h3>
     * A caller outside the caseload gets the same 404 as before, so this cannot be used to read an
     * arbitrary case by guessing an id; the three grants above all require a tie to <em>this patient</em>
     * that the caller already had. Nothing else moves either: {@link #record}, {@link #casesFor},
     * {@link #updateCase} and both writes keep the live-only read, so an archived case is readable and
     * is not editable, and its patient's record does not open.
     *
     * <p>And what was rejected is the wider reading again: computing {@code assignedToMeLive} over
     * archived rows too. That would let a clinician whose last case with a patient was archived go on
     * reading that patient's <em>current</em> cases, written by whoever has them now — continuing
     * access to a record after involvement ended, which is a product decision nobody has taken and not
     * one to smuggle in under a javadoc correction. It is one predicate away and
     * {@code readingBackMYarchivedCaseIsNOTcontinuingAccessToThePatient} is what keeps it away.
     */
    public CaseDetail caseDetail(String patientId, String caseId) {
        String professionalId = callerProfileId().orElse(null);
        if (professionalId == null || patientId == null || patientId.isBlank()) {
            // Same refusal entitledCases makes without reading anything: there is no caseload question
            // to ask, and a scoped read with no id would be an estate-wide one.
            throw new PatientNotInCaseloadException(patientId);
        }
        // The read comes first, as in requireEntitlement: it raises on a failure, so a 503 is never
        // dressed up as "no such case for this clinician" (item 24). The in-memory patientId filter is
        // load-bearing here for the same reason it is there — these rows decide an entitlement, and a
        // sibling that ignored the parameter would otherwise hand back the estate.
        List<ClinicalCase> cases = patientService
            .casesIncludingArchived(patientId)
            .stream()
            .filter(c -> patientId.equals(c.patientId()))
            .toList();
        ClinicalCase found = cases
            .stream()
            .filter(c -> caseId.equals(c.id()))
            .findFirst()
            .orElseThrow(() -> new PatientNotInCaseloadException(patientId));
        if (!mayRead(professionalId, patientId, found, cases)) {
            throw new PatientNotInCaseloadException(patientId);
        }
        return new CaseDetail(
            found.id(),
            found.patientId(),
            found.caseNumber(),
            found.title(),
            text(found.openedAt()),
            text(found.closedAt()),
            found.brief(),
            found.status() == null ? null : found.status().toLowerCase(java.util.Locale.ROOT),
            found.symptoms(),
            found.diagnosis(),
            text(found.archivedAt())
        );
    }

    /**
     * Whether this caller may read this one case (backlog item 82) — the three grants
     * {@link #caseDetail}'s javadoc names, in the order they are evaluated.
     *
     * <p><b>{@code assignedToMeLive} and {@code scheduledWith} are the ordinary caseload rule</b>, and
     * it is the same rule {@link #entitledCases} applies: the case half over <b>live rows only</b>
     * ({@code archivedAt == null}, which is the sibling's own definition of live — its non-archived
     * branch queries {@code findByPatientIdAndArchivedAtIsNull}), the task half identical. Fetching
     * archived rows above therefore changes which case can be <em>found</em> and not who is entitled to
     * the patient — but because {@code found} may now be an archived row, passing this rule reaches
     * further than it did: that is G1 and G2, and they are stated there rather than buried here.
     *
     * <p><b>{@code thisCaseIsMine} is the addition</b> (G3). For a live case it is already covered by
     * the first arm, so it only ever grants an <em>archived</em> case to the clinician it was assigned
     * to, with no other tie to the patient.
     *
     * <p><b>This is a duplicate of {@link #entitledCases}'s rule and duplicates drift.</b> It is
     * deliberate — see {@link #requireEntitlement} for why parameterising that method was rejected —
     * and the cost is that narrowing the rule there no longer narrows it here, or the reverse. Both
     * caseload arms are covered by tests of their own for exactly that reason; deleting either one
     * reddens a case that names it.
     */
    private boolean mayRead(String professionalId, String patientId, ClinicalCase found, List<ClinicalCase> cases) {
        boolean assignedToMeLive = cases
            .stream()
            .anyMatch(c -> c.archivedAt() == null && professionalId.equals(c.assignedProfessionalId()));
        boolean thisCaseIsMine = professionalId.equals(found.assignedProfessionalId());
        return assignedToMeLive || thisCaseIsMine || scheduledWith(professionalId, patientId);
    }

    /** The clinical fields a clinician may edit. Everything else on a case is somebody else's. */
    public record CaseUpdate(String symptoms, String diagnosis, String brief, String status) {}

    private CaseSummary toCaseSummary(ClinicalCase c) {
        return new CaseSummary(
            c.id(),
            c.patientId(),
            text(c.openedAt()),
            c.brief(),
            c.status() == null ? null : c.status().toLowerCase(java.util.Locale.ROOT)
        );
    }

    /** Pages an already-assembled list. Same shape as {@link #directory(Pageable, DirectoryFilter)}. */
    private <T> Page<T> slice(List<T> all, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(all, pageable, all.size());
        }
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    /**
     * The caller's login and this patient's cases, having established the two belong together.
     *
     * <p><b>Refusal and unavailability are two different answers</b> (backlog item 24).
     * {@link #entitledCases} raises {@link PatientServiceUnavailableException} when the patient's cases
     * could not be read, and that propagates as a 503; {@link PatientNotInCaseloadException} below is
     * therefore only ever a statement about a caseload this service actually saw. Catching the first
     * and folding it into the second is exactly the defect item 24 exists to close.
     *
     * <p><b>It hands back the cases rather than discarding them</b> (backlog item 23). Three of the
     * five callers went on to read the same collection again for the answer they were computing, which
     * was two estate-wide reads per request before item 22 and roughly fourteen HTTP requests after it.
     * Returning the rows the check already read is the whole fix, and it is preferred here to a
     * request-scoped cache over {@link PatientServiceClient}: a cache would have to be keyed on the
     * path <em>and</em> the filter, be invalidated by the writes in this same class, and be reasoned
     * about by everyone who later adds a read — for a saving that is one parameter and one return type
     * in the place where the duplication actually is. The two callers that want only the login pay one
     * scoped read they did not before, and it replaces an estate-wide one.
     *
     * <p><b>Four callers now, not five.</b> {@link #caseDetail} left this path in backlog item 82: it
     * needs the patient's cases <em>including archived ones</em>, which no other caller wants and which
     * this method must not start fetching — the rows it returns decide an entitlement and are served as
     * a list by two of the callers above. It makes the same single scoped read, checks the same
     * caseload rule over the same live rows, and adds one narrower grant of its own; the shape here is
     * deliberately duplicated rather than parameterised, because a boolean on this method would put
     * "may I see retired cases" and "am I entitled to this patient" in one signature.
     */
    private Entitlement requireEntitlement(String patientId) {
        String professionalId = callerProfileId().orElse(null);
        List<ClinicalCase> cases = entitledCases(professionalId, patientId).orElseThrow(() -> new PatientNotInCaseloadException(patientId));
        String accountId = SecurityUtils.getCurrentAccountId().orElseThrow(() -> new PatientNotInCaseloadException(patientId));
        return new Entitlement(accountId, cases);
    }

    /** What {@link #requireEntitlement} established, and what it read to establish it. */
    private record Entitlement(String accountId, List<ClinicalCase> cases) {}

    /**
     * This patient's cases, if the caller works with this patient; empty if they do not.
     *
     * <p><b>A membership question, not a set-shaped one, which is why it can be asked cheaply</b>
     * (backlog item 23). "Is this patient mine" is answered by the same union as the directory — a
     * {@code Task} here naming the caller as attendant, or a {@code ClinicalCase} over there naming
     * them as assigned professional — but it needs only <em>this</em> patient's rows, and
     * {@code /api/clinical-cases} takes a {@code patientId}. So it is a scoped read of a handful of
     * rows rather than {@link #patientIdsFor}'s estate-wide one, and it produces exactly the same
     * answer: the caller is in the union for this patient iff one of these cases is assigned to them
     * or one of their tasks names the patient.
     *
     * <p><b>The read happens before the task half is consulted, deliberately.</b> Checking tasks first
     * and returning early would make a task-entitled patient readable during a patientservice outage —
     * plausibly an improvement, and not this change's to make: it would turn a 503 into a 200 carrying
     * a record with no cases, no activity and no medications, which is the "empty is a fact" conflation
     * item 24 closed, wearing a different hat. Behaviour here is unchanged: an unreadable sibling is a
     * 503 for every patient.
     *
     * <p>A caller with no profile, or no patient named, is refused without reading anything. There is
     * no caseload question to ask, and a scoped read with no id would be an estate-wide one.
     *
     * <p><b>The rows are narrowed again here, and that one is not redundant.</b> Everywhere else in
     * this class the in-memory {@code patientId} filter is belt-and-braces over a query the sibling has
     * already scoped; on this path it is load-bearing, because these rows decide an <em>entitlement</em>.
     * A sibling that ignored the parameter — the same failure mode {@code getAll}'s page guard exists
     * for — would otherwise hand back the estate, and any clinician holding one case anywhere would be
     * entitled to every patient. An authorization rule may not rest on another service honouring a
     * query parameter.
     */
    private Optional<List<ClinicalCase>> entitledCases(String professionalId, String patientId) {
        if (professionalId == null || patientId == null || patientId.isBlank()) {
            return Optional.empty();
        }
        List<ClinicalCase> cases = patientService.clinicalCases(patientId).stream().filter(c -> patientId.equals(c.patientId())).toList();
        boolean assignedToMe = cases.stream().anyMatch(c -> professionalId.equals(c.assignedProfessionalId()));
        return assignedToMe || scheduledWith(professionalId, patientId) ? Optional.of(cases) : Optional.empty();
    }

    /** The task half of the caseload union: a visit in this service naming the caller for this patient. */
    private boolean scheduledWith(String professionalId, String patientId) {
        return taskRepository.findByAttendantId(professionalId).stream().anyMatch(task -> patientId.equals(task.getPatientId()));
    }

    /**
     * A previous forward of this {@code clientRef}, if there was one.
     *
     * <p>A key replayed by a different account or against a different patient is refused rather than
     * honoured: it means two clients generated the same key, and answering with the first one's
     * record would hand one clinician another's write.
     */
    private Optional<PatientWriteReceipt> replayOf(String clientRef, String accountId, String patientId, String kind) {
        if (clientRef == null || clientRef.isBlank()) {
            return Optional.empty();
        }
        return receiptRepository
            .findByClientRef(clientRef)
            .map(receipt -> {
                if (
                    !accountId.equals(receipt.getAccountId()) ||
                    !patientId.equals(receipt.getPatientId()) ||
                    !kind.equals(receipt.getKind())
                ) {
                    throw new IllegalArgumentException("clientRef has already been used for a different write");
                }
                return receipt;
            });
    }

    private void recordReceipt(String clientRef, String accountId, String patientId, String kind, String createdId) {
        if (clientRef == null || clientRef.isBlank() || createdId == null) {
            return;
        }
        PatientWriteReceipt receipt = new PatientWriteReceipt();
        receipt.setClientRef(clientRef);
        receipt.setAccountId(accountId);
        receipt.setPatientId(patientId);
        receipt.setKind(kind);
        receipt.setCreatedId(createdId);
        receipt.setCreatedAt(Instant.now());
        try {
            receiptRepository.save(receipt);
        } catch (DuplicateKeyException e) {
            // Two retries of the same queued write raced and both missed the read. The record is
            // already filed once, which is the outcome that matters; the loser simply stops here.
            log.debug("Receipt for clientRef {} already stored by a concurrent retry", clientRef);
        }
    }

    /**
     * Re-reads a filed activity so a replay answers with the record rather than a fresh copy.
     *
     * <p><b>Through the strict {@link #record}, because this answer <em>is</em> made of the activity
     * log.</b> A caller refused that collection cannot be told what their filed entry says, and the
     * honest report of that is the refusal — an entry omitted from a {@code 201} would say the write had
     * produced nothing.
     *
     * <p><b>Whether it is reachable at all is an open assumption, stated rather than relied on.</b> This
     * path needs a discipline that may <em>write</em> to {@code /api/activity-logs} and may not read it;
     * no such discipline has been observed, and a pharmacist's write was not probed because the quality
     * stack is read-only to this work. If one exists, the 503 here is correct for the reason above. If
     * none does, this line is unreachable and costs nothing. Either way it does not rest on the mistake
     * {@link #reportById} used to make.
     */
    private ActivityLogEntry activityById(String patientId, String id) {
        return record(patientId)
            .map(PatientRecord::activities)
            .orElseGet(List::of)
            .stream()
            .filter(entry -> entry.id() != null && entry.id().equals(id))
            .findFirst()
            .orElse(null);
    }

    /**
     * Re-reads a filed report so a replay answers with the record rather than a fresh copy.
     *
     * <p><b>From the report collection alone, since backlog item 112's review.</b> It went through
     * {@link #record} until then, and so through {@code /api/activity-logs} — a collection this answer is
     * not made of. The consequence was live and permanent: a pharmacist may write to {@code /api/reports}
     * and is refused {@code /api/activity-logs}, both measured, so a filed report that was fully readable
     * to them answered <b>503 on every replay for ever</b> — and {@code mobile/}'s offline queue replays
     * by {@code clientRef} as a matter of course.
     *
     * <p><b>It is this item's own argument, applied to the path the item did not follow.</b> The dashboard
     * summary tolerates a refused activity log because no figure of it is computed from that collection;
     * the same sentence is true of a report, and was not acted on here until the review pointed at it.
     *
     * <p>No entitlement check is repeated and none is skipped: both callers reach this only after
     * {@link #requireEntitlement} has already passed for this patient, which is what made the record read
     * redundant rather than protective.
     */
    private ClinicalReport reportById(String patientId, String id) {
        return clinicalReports(patientId).stream().filter(entry -> entry.id() != null && entry.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * One patient's clinical reports, mapped into the record's shape.
     *
     * <p>Shared by {@link #assemble} and {@link #reportById} rather than copied into the second: the two
     * would otherwise drift in how a report's date falls back, and a replay would start answering with a
     * differently-shaped entry than the record shows. The in-memory {@code patientId} filter is kept for
     * the reason given on the activity read — it costs nothing over one patient's rows and makes the
     * answer independent of the far side honouring a parameter.
     */
    private List<ClinicalReport> clinicalReports(String patientId) {
        return patientService
            .reports(patientId)
            .stream()
            .filter(r -> patientId.equals(r.patientId()))
            .map(
                r ->
                    new ClinicalReport(
                        r.id(),
                        occurredAt(null, r.reportDate() == null ? r.createdDate() : r.reportDate()),
                        r.name(),
                        r.category(),
                        r.url()
                    )
            )
            .toList();
    }

    private Optional<Instant> parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(iso.trim()));
        } catch (java.time.format.DateTimeParseException e) {
            // A malformed timestamp is not worth refusing a clinical note over. Treated as absent,
            // which means "now" — and patientservice stamps the audit fields either way.
            log.debug("Ignoring unparseable occurredAt '{}'", iso);
            return Optional.empty();
        }
    }

    /**
     * Thrown when a caller writes to a patient outside their caseload. Mapped to 404 by the resource.
     *
     * <p>It means the caseload was read and this patient was not in it. It does <b>not</b> mean the
     * caseload could not be read — that is {@link PatientServiceUnavailableException} and a 503. The
     * two were the same answer until backlog item 24.
     */
    public static class PatientNotInCaseloadException extends RuntimeException {

        public PatientNotInCaseloadException(String patientId) {
            super("No such patient for this clinician: " + patientId);
        }
    }

    /**
     * The figures this service can answer alone: how many patients the clinician has, split by sex
     * and by child/adult. Case counts are patientservice's and are composed in the browser.
     *
     * <p><b>Strict about every read a count is made of, and about no other</b> (backlog items 107 and
     * 112). This was on a fully strict composition until 2026-09-14, so a pharmacist and a chemist
     * got a 503 for a dashboard every figure of which this service could compute exactly — refused over
     * {@code /api/activity-logs}, which none of the four figures is made of. It is now on
     * {@link #countableCaseload()}, which tolerates that one refusal and nothing else: the case half of
     * the caseload union still raises, because losing it makes all four counts smaller than the truth
     * with nowhere in a flat object of numbers to say so. That paragraph is item 111's Decision C and it
     * is unchanged; what moved is which reads it reaches, and {@link #countableCaseload()} argues it.
     */
    public DashboardSummary summary() {
        List<PatientListItem> directory = countableCaseload();
        long female = directory.stream().filter(p -> "female".equalsIgnoreCase(p.sex())).count();
        long male = directory.stream().filter(p -> "male".equalsIgnoreCase(p.sex())).count();
        long kids = directory.stream().filter(PatientListItem::isChild).count();
        return new DashboardSummary(directory.size(), female, male, kids);
    }

    /**
     * One patient's profile, scoped by the sibling rather than sieved out of the estate's.
     *
     * <p>Separate from {@link #profilesByPatientId} because the two are different questions:
     * {@code /api/profiles} takes one {@code patientId} and cannot express a set, so the directory's
     * many-patient read stays estate-wide (backlog item 23) while this one costs a single scoped page.
     *
     * <p>First row wins, as in the map below: two profile documents for one patient is a data fault in
     * the sibling, and throwing over it would take out a patient record that is otherwise readable.
     */
    private Optional<PatientProfile> patientProfile(String patientId) {
        return patientService.profiles(patientId).stream().filter(profile -> patientId.equals(profile.patientId())).findFirst();
    }

    /**
     * Profiles for a set of patients, indexed by {@code patientId}.
     *
     * <p><b>Estate-wide, and it is the directory's share of backlog item 23's cross-stack half.</b> The
     * sibling filters by one patient at a time, so narrowing this would mean one request per patient —
     * a caseload of a hundred against the three pages it takes to read ~600 profiles. The arithmetic
     * only works with a filter that takes a set, which does not exist over there. Use
     * {@link #patientProfile} whenever the question really is about one patient.
     */
    private Map<String, PatientProfile> profilesByPatientId(Set<String> patientIds) {
        return patientService
            .profiles()
            .stream()
            .filter(profile -> profile.patientId() != null && patientIds.contains(profile.patientId()))
            // A patient with two profile documents is a data fault in the sibling service; keep the
            // first rather than throwing, so one bad row cannot empty a clinician's whole directory.
            .collect(
                Collectors.toMap(PatientProfile::patientId, Function.identity(), (first, second) -> first, java.util.LinkedHashMap::new)
            );
    }

    /**
     * Most recent activity per patient, used to order the directory.
     *
     * <p>Estate-wide for {@link #profilesByPatientId}'s reason: the ordering is over the whole
     * directory, and one scoped read per patient would cost a request each (backlog item 23).
     */
    private Map<String, String> lastActivityByPatient() {
        return patientService
            .activityLogs()
            .stream()
            .filter(a -> a.patientId() != null && (a.loggedAt() != null || a.createdDate() != null))
            .collect(
                Collectors.toMap(
                    net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog::patientId,
                    a -> occurredAt(a.loggedAt(), a.createdDate()),
                    (a, b) -> a.compareTo(b) >= 0 ? a : b
                )
            );
    }

    /**
     * When something happened, preferring the clinical timestamp over the filing date.
     *
     * <p>Both are rendered as text because that is what the frontend contract carries, and both sort
     * lexicographically in ISO-8601 — but they do not sort against <em>each other</em>:
     * {@code "2026-08-22"} precedes {@code "2026-08-22T09:15:00Z"} for the same moment. Mixing the
     * two within one list is therefore avoided rather than tolerated; each list above picks one kind.
     */
    private String occurredAt(java.time.Instant clinical, LocalDate filed) {
        if (clinical != null) {
            return clinical.toString();
        }
        return filed == null ? null : filed.toString();
    }

    private PatientListItem toListItem(PatientProfile profile, String lastActivityAt) {
        return new PatientListItem(
            profile.patientId(),
            profile.fullName(),
            lastActivityAt,
            normaliseSex(profile.sex()),
            isChild(profile.birthDate())
        );
    }

    private EmergencyContact emergencyContact(PatientProfile profile) {
        // patientservice stores contacts as free text, not a structured pair — phase_4 flags this as
        // an unresolved contract. Surfaced as the name with no phone rather than parsed on a guess.
        String contacts = profile.contacts();
        return contacts == null || contacts.isBlank() ? null : new EmergencyContact(contacts, null);
    }

    /** The frontend's PatientSexDto is a closed set; anything unrecognised becomes 'unspecified'. */
    private String normaliseSex(String sex) {
        if (sex == null) {
            return "unspecified";
        }
        String lower = sex.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "female", "f" -> "female";
            case "male", "m" -> "male";
            default -> "unspecified";
        };
    }

    private boolean isChild(LocalDate birthDate) {
        return birthDate != null && Period.between(birthDate, LocalDate.now(ZoneOffset.UTC)).getYears() < CHILD_AGE_LIMIT;
    }

    private String text(Object temporal) {
        return temporal == null ? null : temporal.toString();
    }
}
