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
        return SecurityUtils.getCurrentUserLogin().flatMap(profileRepository::findByAccountId).map(net.jojoaddison.domain.Profile::getId);
    }

    /** Patient ids the clinician has worked with, from tasks here and cases in patientservice. */
    public Set<String> patientIdsFor(String professionalId) {
        Stream<String> fromTasks = taskRepository.findByAttendantId(professionalId).stream().map(Task::getPatientId);
        Stream<String> fromCases = patientService
            .clinicalCases()
            .stream()
            .filter(clinicalCase -> professionalId.equals(clinicalCase.assignedProfessionalId()))
            .map(net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase::patientId);
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
     */
    public Optional<PatientRecord> record(String patientId) {
        String professionalId = callerProfileId().orElse(null);
        if (professionalId == null || !patientIdsFor(professionalId).contains(patientId)) {
            return Optional.empty();
        }
        PatientProfile profile = profilesByPatientId(Set.of(patientId)).get(patientId);
        if (profile == null) {
            return Optional.empty();
        }

        List<CaseSummary> cases = patientService
            .clinicalCases()
            .stream()
            .filter(c -> patientId.equals(c.patientId()))
            .map(
                c ->
                    new CaseSummary(
                        c.id(),
                        text(c.openedAt()),
                        c.brief(),
                        c.status() == null ? null : c.status().toLowerCase(java.util.Locale.ROOT)
                    )
            )
            .toList();

        // occurredAt is the clinical time — when the thing happened — falling back to the filing
        // date. Ordering a record by when it was typed rather than when it happened puts a
        // late-entered observation at the top, which reads as the most recent event.
        List<ActivityLogEntry> activities = patientService
            .activityLogs()
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
            .medications()
            .stream()
            .filter(m -> patientId.equals(m.patientId()))
            .map(m -> new RecordEntry(m.id(), occurredAt(null, m.startedOn() == null ? m.createdDate() : m.startedOn()), m.name()))
            .toList();

        List<ClinicalReport> reports = patientService
            .reports()
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
        String accountId = requireEntitlement(patientId);

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
        String accountId = requireEntitlement(patientId);

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
     * of this method. The sibling's {@code /api/clinical-cases} is generated CRUD with no filters and
     * no clinician scope: a client that calls it gets <em>every case in the estate</em> and narrows
     * the list in the browser. Going through here means the narrowing is server-side and the caller
     * never receives a case that is not theirs.
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
            .sorted(
                Comparator.comparing(
                    net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase::openedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
                )
            )
            .map(this::toCaseSummary)
            .toList();
        return slice(matches, pageable);
    }

    /** One patient's cases, entitlement-checked the same way their record is. */
    public Page<CaseSummary> casesFor(String patientId, Pageable pageable) {
        requireEntitlement(patientId);
        List<CaseSummary> matches = patientService
            .clinicalCases()
            .stream()
            .filter(c -> patientId.equals(c.patientId()))
            .filter(c -> c.archivedAt() == null)
            .sorted(
                Comparator.comparing(
                    net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase::openedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
                )
            )
            .map(this::toCaseSummary)
            .toList();
        return slice(matches, pageable);
    }

    /**
     * Updates the clinical fields of one of the caller's own cases.
     *
     * <p><b>This exists for a security reason, not for tidiness.</b> patientservice's PATCH is gated
     * on its own {@code requireWrite}, which passes for any authenticated non-patient caller —
     * verified against the deployed stack, where a <em>carer</em> receives 400 rather than 403 from
     * it. So a role that is read-only in this service can edit a diagnosis by going through the
     * gateway's {@code patientservice} route directly. Routed through here it is behind
     * {@code CLINICAL_MUTATION} <em>and</em> the caseload check, which is the posture this service
     * already applies to every other clinical write.
     *
     * <p>Only the four fields a clinician edits are forwarded. A whole-document PATCH would let a
     * caller move a case to another patient or reassign it, neither of which is this screen's job.
     */
    public CaseSummary updateCase(String patientId, String caseId, CaseUpdate changes) {
        requireEntitlement(patientId);

        net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase existing = patientService
            .clinicalCases()
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

    /** The clinical fields a clinician may edit. Everything else on a case is somebody else's. */
    public record CaseUpdate(String symptoms, String diagnosis, String brief, String status) {}

    private CaseSummary toCaseSummary(net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ClinicalCase c) {
        return new CaseSummary(
            c.id(),
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

    /** The caller's login, having established they may write to this patient. */
    private String requireEntitlement(String patientId) {
        String professionalId = callerProfileId().orElse(null);
        if (professionalId == null || patientId == null || !patientIdsFor(professionalId).contains(patientId)) {
            throw new PatientNotInCaseloadException(patientId);
        }
        return SecurityUtils.getCurrentUserLogin().orElseThrow(() -> new PatientNotInCaseloadException(patientId));
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

    /** Thrown when a caller writes to a patient outside their caseload. Mapped to 404 by the resource. */
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

    /** Most recent activity per patient, used to order the directory. */
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
