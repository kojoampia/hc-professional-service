package net.jojoaddison.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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

    public PatientDirectoryService(
        TaskRepository taskRepository,
        ProfileRepository profileRepository,
        PatientServiceClient patientService,
        PatientWriteReceiptRepository receiptRepository
    ) {
        this.taskRepository = taskRepository;
        this.profileRepository = profileRepository;
        this.patientService = patientService;
        this.receiptRepository = receiptRepository;
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
     */
    public Page<PatientListItem> directory(Pageable pageable, DirectoryFilter filter) {
        DirectoryFilter effective = filter == null ? DirectoryFilter.NONE : filter;
        List<PatientListItem> matches = directory().stream().filter(effective::matches).toList();

        List<PatientListItem> ordered = sort(matches, pageable.getSort());
        if (pageable.isUnpaged()) {
            return new PageImpl<>(ordered, pageable, ordered.size());
        }

        int from = (int) Math.min(pageable.getOffset(), ordered.size());
        int to = Math.min(from + pageable.getPageSize(), ordered.size());
        return new PageImpl<>(ordered.subList(from, to), pageable, ordered.size());
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

    /** The clinician's patient directory, newest activity first. */
    public List<PatientListItem> directory() {
        String professionalId = callerProfileId().orElse(null);
        if (professionalId == null) {
            return List.of();
        }
        Set<String> patientIds = patientIdsFor(professionalId);
        if (patientIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> lastActivity = lastActivityByPatient();
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
     * @throws PatientServiceUnavailableException when the sibling could not be read
     */
    public Optional<PatientRecord> record(String patientId) {
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

        // occurredAt is the clinical time — when the thing happened — falling back to the filing
        // date. Ordering a record by when it was typed rather than when it happened puts a
        // late-entered observation at the top, which reads as the most recent event.
        //
        // The in-memory patientId filters below are kept although the sibling now scopes the query:
        // they cost nothing over one patient's rows and they are what makes this method's answer
        // independent of the far side honouring a parameter. They are no longer the mechanism.
        List<ActivityLogEntry> activities = patientService
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

        List<RecordEntry> medications = patientService
            .medications(patientId)
            .stream()
            .filter(m -> patientId.equals(m.patientId()))
            .map(m -> new RecordEntry(m.id(), occurredAt(null, m.startedOn() == null ? m.createdDate() : m.startedOn()), m.name()))
            .toList();

        List<ClinicalReport> reports = patientService
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
     * pay this cost, but this one and {@link #directory()} still do.
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

    /** Re-reads a filed entry so a replay answers with the record rather than a fresh copy. */
    private ActivityLogEntry activityById(String patientId, String id) {
        return record(patientId)
            .map(PatientRecord::activities)
            .orElseGet(List::of)
            .stream()
            .filter(entry -> entry.id() != null && entry.id().equals(id))
            .findFirst()
            .orElse(null);
    }

    private ClinicalReport reportById(String patientId, String id) {
        return record(patientId)
            .map(PatientRecord::reports)
            .orElseGet(List::of)
            .stream()
            .filter(entry -> entry.id() != null && entry.id().equals(id))
            .findFirst()
            .orElse(null);
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
     */
    public DashboardSummary summary() {
        List<PatientListItem> directory = directory();
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
