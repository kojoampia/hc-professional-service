package net.jojoaddison.service;

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
import net.jojoaddison.domain.Task;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.repository.TaskRepository;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.dto.PatientDtos.ActivityLogEntry;
import net.jojoaddison.service.dto.PatientDtos.CaseSummary;
import net.jojoaddison.service.dto.PatientDtos.ClinicalReport;
import net.jojoaddison.service.dto.PatientDtos.DashboardSummary;
import net.jojoaddison.service.dto.PatientDtos.EmergencyContact;
import net.jojoaddison.service.dto.PatientDtos.PatientListItem;
import net.jojoaddison.service.dto.PatientDtos.PatientRecord;
import net.jojoaddison.service.dto.PatientDtos.RecordEntry;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
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

    private final TaskRepository taskRepository;
    private final ProfileRepository profileRepository;
    private final PatientServiceClient patientService;

    public PatientDirectoryService(
        TaskRepository taskRepository,
        ProfileRepository profileRepository,
        PatientServiceClient patientService
    ) {
        this.taskRepository = taskRepository;
        this.profileRepository = profileRepository;
        this.patientService = patientService;
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

        List<ActivityLogEntry> activities = patientService
            .activityLogs()
            .stream()
            .filter(a -> patientId.equals(a.patientId()))
            .map(a -> new ActivityLogEntry(a.id(), text(a.createdDate()), a.name(), a.name(), a.description(), text(a.createdDate())))
            .toList();

        List<RecordEntry> medications = patientService
            .medications()
            .stream()
            .filter(m -> patientId.equals(m.patientId()))
            .map(m -> new RecordEntry(m.id(), text(m.createdDate()), m.name()))
            .toList();

        List<ClinicalReport> reports = patientService
            .reports()
            .stream()
            .filter(r -> patientId.equals(r.patientId()))
            .map(r -> new ClinicalReport(r.id(), text(r.createdDate()), r.name(), r.category(), r.url()))
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
            .filter(a -> a.patientId() != null && a.createdDate() != null)
            .collect(
                Collectors.toMap(
                    net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog::patientId,
                    a -> text(a.createdDate()),
                    (a, b) -> a.compareTo(b) >= 0 ? a : b
                )
            );
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
