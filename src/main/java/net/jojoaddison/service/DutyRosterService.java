package net.jojoaddison.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jojoaddison.domain.Absence;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Visit;
import net.jojoaddison.domain.enumeration.AbsenceStatus;
import net.jojoaddison.domain.enumeration.AbsenceType;
import net.jojoaddison.domain.enumeration.ShiftType;
import net.jojoaddison.repository.AbsenceRepository;
import net.jojoaddison.repository.DutyRosterRepository;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.PatientProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Duty-roster rounds: window resolution, overlap rules, range and year reads, and the 90-day
 * snapshot purge (docs/duty-roster.md §§ 4–6, DR2).
 *
 * <p>The resource was talking to the repository directly and could go on doing so while a roster row
 * was six scalar fields. A round is not: whether a visit is legal depends on the shift's window, on
 * the midnight wrap, and on every other round that professional already holds. That is domain logic
 * and it lives here, where it can be tested without a controller.
 *
 * <p><b>The wrap is the thing to get right.</b> {@code NIGHT} runs 23:00 on the assignment date until
 * 07:00 the next, so a visit at 01:00 belongs to the <em>previous</em> date's shift, and the same
 * clock time means different instants under different shifts. Every rule below therefore works on
 * resolved {@link LocalDateTime}s from {@link #resolve}, never on bare {@link LocalTime}s. Comparing
 * clock times directly is the specific mistake this class exists to prevent: it looks right, passes a
 * casual test, and rejects a legitimate 23:30→00:30 call while admitting an overlapping one.
 */
@Service
public class DutyRosterService {

    private static final Logger log = LoggerFactory.getLogger(DutyRosterService.class);

    /**
     * How long a visit's personal-data snapshot survives (docs/duty-roster.md § 6).
     *
     * <p>Counted from the round's date, not from when the row was written — what matters is how old
     * the care episode is, not when an administrator happened to enter it.
     */
    public static final int SNAPSHOT_RETENTION_DAYS = 90;

    private final DutyRosterRepository dutyRosterRepository;
    private final AbsenceRepository absenceRepository;
    private final PatientServiceClient patientServiceClient;

    public DutyRosterService(
        DutyRosterRepository dutyRosterRepository,
        AbsenceRepository absenceRepository,
        PatientServiceClient patientServiceClient
    ) {
        this.dutyRosterRepository = dutyRosterRepository;
        this.absenceRepository = absenceRepository;
        this.patientServiceClient = patientServiceClient;
    }

    /** Raised for a round the caller cannot have; the resource turns it into a 400. */
    public static class InvalidRoundException extends RuntimeException {

        public InvalidRoundException(String message) {
            super(message);
        }
    }

    /**
     * One day of the caller's year (DR2 summary read).
     *
     * <p>Carries the shifts worked and how many visits they held — enough for a year grid to colour a
     * day and show a density, without shipping the rounds themselves, which would mean shipping a
     * year of customer snapshots to render twelve small squares.
     *
     * <p><b>{@code absence} was added in DR4</b>, which is what lets the year view finally distinguish
     * the three states § 8 describes. Until then "off" and "not yet rostered" were the same thing — a
     * day missing from the result — and they still are when {@code absence} is null. A day can carry
     * both: a round <em>and</em> an absence over it means leave was requested but the cover has not
     * been arranged, and that is precisely the day an administrator needs to see, so neither field
     * suppresses the other.
     */
    public record DaySummary(LocalDate date, List<ShiftType> shifts, int visits, AbsenceOnDay absence) {}

    /**
     * The absence covering a day, if any.
     *
     * <p>Status is carried, not just type, because {@code REQUESTED} renders hatched and
     * {@code APPROVED} solid — a clinician has to be able to see that time off is asked for rather
     * than granted, and colour alone cannot say it.
     */
    public record AbsenceOnDay(AbsenceType type, AbsenceStatus status) {}

    /** What a purge run did. Rounds are counted, not visits, so the number matches the audit line. */
    public record PurgeResult(int roundsPurged, int visitsPurged) {}

    // ---------------------------------------------------------------- reads

    /**
     * The caller's own roster, optionally narrowed to a date range.
     *
     * <p>Both bounds are optional and independent: neither means the whole roster, which is what DR1
     * returned and what the web client still asks for. Passing only one bounds that end alone.
     */
    public List<DutyRoster> forProfessional(String professionalId, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRoundException("'from' must not be after 'to'");
        }
        // A missing bound picks a different finder rather than a sentinel date. Substituting
        // LocalDate.MIN/MAX and reusing the two-sided query returns an empty roster instead of an
        // open-ended one — see the note on the repository.
        if (from == null && to == null) {
            return dutyRosterRepository.findByProfessionalIdOrderByDateAscShiftAsc(professionalId);
        }
        if (to == null) {
            return dutyRosterRepository.findByProfessionalIdAndDateGreaterThanEqualOrderByDateAscShiftAsc(professionalId, from);
        }
        if (from == null) {
            return dutyRosterRepository.findByProfessionalIdAndDateLessThanEqualOrderByDateAscShiftAsc(professionalId, to);
        }
        return dutyRosterRepository.findRoundsInRange(professionalId, from, to);
    }

    /**
     * The estate read's default ordering: <b>newest date first</b>, then shift, then id.
     *
     * <p><b>Descending, and that is a deliberate reversal.</b> It was {@code Sort.by("date", "shift")}
     * — ascending — for the first day of item 7, which meant page 0 of a paged read was the most
     * ancient assignments the estate has ever held. The estate accumulates history and is never
     * pruned, so the failure was immediate and read as a lost write: an administrator creates a round
     * for tomorrow, the list refreshes onto page 0, and twenty rounds from months ago come back with
     * the new one nine pages away. Nothing was wrong and the screen said the create had not taken.
     * Unpaginated it could not happen, because everything arrived and the administrator could scroll.
     *
     * <p>This list is an administrator's <em>working set</em> — the thing being built, moved or
     * removed is nearly always near today — and newest-first is the working order. The decision lives
     * here rather than in the client on purpose: the web list sends no {@code sort} at all and holds
     * no copy of this, so there is one place to change it and no second copy to drift.
     *
     * <p><b>{@code id} is the tiebreaker, and it is what makes the paging claim true.</b> {@code date}
     * and {@code shift} together are very non-unique — every professional rostered on the same date
     * and shift ties, which is the ordinary shape of an estate, not an edge of it — and Mongo
     * guarantees no order among tied documents across two separate queries. A page boundary falling
     * inside a tie group could therefore repeat a row on page 2 or drop it entirely, which is exactly
     * the failure this docstring used to warn about while choosing an ordering that had it.
     */
    private static final Sort DEFAULT_ESTATE_SORT = Sort.by(Sort.Order.desc("date"), Sort.Order.asc("shift"), Sort.Order.asc("id"));

    /**
     * Every assignment on the estate, one page at a time — the administrator's read.
     *
     * <p><b>Paginated because it grows with the roster, not with the number of professionals.</b> It
     * was an unbounded {@code findAll} until item 7: admin-gated, but one response carrying every
     * round the estate has ever had, and the collection only ever gets longer. The bound belongs on
     * the server, since a client that forgets to ask for one is exactly the client that cannot cope
     * with the answer.
     *
     * <p>Sorting defaults to {@link #DEFAULT_ESTATE_SORT} — newest date first — when the caller names
     * none. A {@code Pageable} with no sort would otherwise page over an <b>unspecified</b> order, and
     * page 2 could repeat or skip rows from page 1 without anything looking wrong.
     *
     * <p>A caller who <em>does</em> name a sort keeps it, and gets {@code id} appended unless they
     * ordered by it themselves. Their ordering is untouched by that — {@code id} is unique, so it can
     * only decide ties the named keys left undecided — and without it a caller-chosen sort has the
     * same non-unique page boundary the default had. A guarantee worth making is not worth making
     * only for the callers who ask for nothing.
     */
    public Page<DutyRoster> estateRoster(Pageable pageable) {
        Sort sort = pageable.getSort().isSorted() ? stableOrder(pageable.getSort()) : DEFAULT_ESTATE_SORT;
        return dutyRosterRepository.findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort));
    }

    /** A caller's sort with {@code id} appended as a total tiebreaker, unless it already orders by id. */
    private static Sort stableOrder(Sort sort) {
        boolean ordersById = sort.stream().anyMatch(order -> "id".equals(order.getProperty()));
        return ordersById ? sort : sort.and(Sort.by(Sort.Order.asc("id")));
    }

    /**
     * One record per day the professional is rostered in the given year, earliest first.
     *
     * <p><b>Only days with something on them</b> — a round, an absence, or both. Returning all 365
     * would make the empty days look like data and triple the payload to say nothing; a year grid
     * renders the gaps as off. Since DR4 an absent day <em>is</em> something, so a granted holiday
     * appears here with no shifts and no visits, which is exactly what the year view colours green.
     */
    public List<DaySummary> summariseYear(String professionalId, int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        Map<LocalDate, List<DutyRoster>> byDate = new TreeMap<>();
        for (DutyRoster round : dutyRosterRepository.findRoundsInRange(professionalId, start, end)) {
            byDate.computeIfAbsent(round.getDate(), key -> new ArrayList<>()).add(round);
        }

        // Absences are stored as ranges and rendered per day, so they are expanded here — clipped to
        // the year, since one can run across a new year and only this year's half belongs in it.
        Map<LocalDate, Absence> absenceByDate = new TreeMap<>();
        for (Absence absence : absenceRepository.findOverlapping(professionalId, start, end)) {
            LocalDate day = absence.getFromDate().isBefore(start) ? start : absence.getFromDate();
            LocalDate last = absence.getToDate().isAfter(end) ? end : absence.getToDate();
            while (!day.isAfter(last)) {
                // An APPROVED absence wins a day it shares with a REQUESTED one: it is the one that
                // actually stops the clinician working, and it is the safer thing to render.
                Absence existing = absenceByDate.get(day);
                if (existing == null || (existing.getStatus() != AbsenceStatus.APPROVED && absence.getStatus() == AbsenceStatus.APPROVED)) {
                    absenceByDate.put(day, absence);
                }
                byDate.computeIfAbsent(day, key -> new ArrayList<>());
                day = day.plusDays(1);
            }
        }

        List<DaySummary> summaries = new ArrayList<>();
        byDate.forEach((date, dayRounds) -> {
            Absence absence = absenceByDate.get(date);
            summaries.add(
                new DaySummary(
                    date,
                    dayRounds.stream().map(DutyRoster::getShift).distinct().sorted().toList(),
                    dayRounds.stream().mapToInt(round -> round.getVisits().size()).sum(),
                    absence == null ? null : new AbsenceOnDay(absence.getType(), absence.getStatus())
                )
            );
        });
        return summaries;
    }

    /**
     * How far either side of today a round still counts as "the caller's own" for the activity trail
     * (docs/duty-roster.md § 7, DR3).
     *
     * <p>Thirty days back is enough to review a recent round, thirty ahead to prepare for an upcoming
     * one. It is a <em>rolling</em> window rather than an accumulating one, so the set of customers a
     * clinician may read shrinks again by itself as rounds age out — nobody has to remember to revoke
     * anything.
     */
    public static final int TRAIL_WINDOW_DAYS = 30;

    /**
     * The customers on the caller's own rounds within {@link #TRAIL_WINDOW_DAYS} either side of a date.
     *
     * <p>This <b>is the authorization boundary</b> for the activity trail, in the same way
     * {@code PatientDirectoryService.patientIdsFor} is the boundary for the patient record. It is
     * computed here, on every read, from this service's own collection — not carried in a token, not
     * asked of another stack. That makes it exact at the moment it is used: a customer removed from
     * the roster loses the trail immediately rather than when some credential next turns over.
     *
     * <p>An empty set is the safe answer and the common one — a professional with no rounds, or an
     * account with no profile at all, reads nothing. Callers must treat empty as "deny", never as
     * "unfiltered".
     */
    public Set<String> trailCustomerIds(String professionalId, LocalDate reference) {
        if (professionalId == null || reference == null) {
            return Set.of();
        }
        return dutyRosterRepository
            .findRoundsInRange(professionalId, reference.minusDays(TRAIL_WINDOW_DAYS), reference.plusDays(TRAIL_WINDOW_DAYS))
            .stream()
            .flatMap(round -> round.getVisits().stream())
            .map(Visit::getCustomerId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The caller's rounds for one date, with their customer snapshots brought up to date
     * (docs/duty-roster.md § 6, DR6).
     *
     * <p><b>This is the only read that refreshes, and the only one that discloses.</b> § 6 says
     * "opening a day fetches the current profiles and updates the stored snapshots behind the render"
     * — a <em>day</em>, not a range. The calendar's range read draws coloured squares and shift names
     * and is served {@code DutyRosterDtos.Round}, which carries no customer at all, so refreshing
     * there would spend a cross-stack call on every page-turn to update data the caller is not even
     * sent. One day, opened deliberately, is where the address is about to be read off the screen and
     * walked to.
     *
     * <p><b>"Needs no customer" was a statement about the client and not about the response, and the
     * gap between those two was item 7.</b> The range read did not need the snapshot and was shipping
     * it anyway. Returning rounds from here rather than a projection is therefore a deliberate
     * exception now, not the default it used to be by accident: callers of this method serve the
     * document, so add one only for a screen that shows an address.
     *
     * <p><b>It is a write on a read path</b>, which § 6 flagged and accepted. Two things keep it
     * honest: only rounds whose snapshot actually changed are saved, so the common case of opening
     * yesterday's round again writes nothing at all; and failure is silent, so an unreachable patient
     * stack yields the stored snapshot rather than an error. A clinician holding a slightly old
     * address can still knock on the door; one holding an error page cannot.
     */
    public List<DutyRoster> dayFor(String professionalId, LocalDate date) {
        if (professionalId == null || date == null) {
            return List.of();
        }
        List<DutyRoster> rounds = dutyRosterRepository.findRoundsInRange(professionalId, date, date);
        refreshSnapshots(rounds);
        return rounds;
    }

    /**
     * Bring the customer snapshots on these rounds up to date, in **one** cross-stack call.
     *
     * <p>This is the answer to open question 2, "batching the on-read refresh". The naive shape is one
     * profile lookup per visit, which is a dozen cross-stack calls to open a busy day; this fetches the
     * collection once and indexes it in memory, exactly as {@link #populateSnapshots} does on the write
     * path. It is a cache rather than a query only because {@code /api/profiles} still takes no filter
     * — when it learns to, this becomes a query and the whole shape gets cheaper.
     *
     * <p><b>An empty profile list is treated as an outage, not as "every customer was deleted".</b>
     * {@link PatientServiceClient} degrades to an empty list when the sibling stack is unreachable, so
     * the two are indistinguishable from here — and of the two readings, blanking every address on the
     * roster is far worse than keeping one that may be a day old. Nothing is cleared on a miss for the
     * same reason: a customer absent from the collection may be new, or the collection may have been
     * truncated by a failure this client is designed to swallow. **Only the 90-day purge clears a
     * snapshot**, and it does so deliberately.
     */
    public int refreshSnapshots(List<DutyRoster> rounds) {
        List<Visit> visits = rounds.stream().flatMap(round -> round.getVisits().stream()).toList();
        if (visits.isEmpty()) {
            return 0;
        }
        Map<String, PatientProfile> byPatientId = new LinkedHashMap<>();
        for (PatientProfile profile : patientServiceClient.profiles()) {
            if (profile.patientId() != null) {
                byPatientId.putIfAbsent(profile.patientId(), profile);
            }
        }
        if (byPatientId.isEmpty()) {
            log.debug("Patient profiles unavailable; serving {} round(s) from their stored snapshots", rounds.size());
            return 0;
        }

        int refreshed = 0;
        for (DutyRoster round : rounds) {
            boolean changed = false;
            for (Visit visit : round.getVisits()) {
                PatientProfile profile = byPatientId.get(visit.getCustomerId());
                if (profile == null) {
                    continue;
                }
                String name = blankToNull(profile.fullName());
                String address = profile.formattedAddress();
                String phone = blankToNull(profile.contactPhone());
                if (!Objects.equals(name, visit.getCustomerName())) {
                    visit.setCustomerName(name);
                    changed = true;
                }
                if (!Objects.equals(address, visit.getCustomerAddress())) {
                    visit.setCustomerAddress(address);
                    changed = true;
                }
                if (!Objects.equals(phone, visit.getCustomerPhone())) {
                    visit.setCustomerPhone(phone);
                    changed = true;
                }
            }
            if (changed) {
                // Identifiers only in the log line — the snapshot is exactly what must not reach one.
                log.debug("Refreshed customer snapshots on round {}", round.getId());
                dutyRosterRepository.save(round);
                refreshed++;
            }
        }
        return refreshed;
    }

    // --------------------------------------------------------------- writes

    /**
     * Validate a round, fill its customer snapshots, and store it.
     *
     * <p>Order matters: validation runs before the cross-stack lookup, so a malformed round costs
     * nothing and a patient-stack outage cannot mask a 400 behind a slow call.
     */
    public DutyRoster assign(DutyRoster round) {
        validateRound(round);
        populateSnapshots(round);
        ensureVisitIds(round);
        return dutyRosterRepository.save(round);
    }

    /**
     * Move a whole round to another professional, visits and all (docs/duty-roster.md § 8, DR4).
     *
     * <p><b>The default form of reassignment, and one auditable action.</b> When someone is off, the
     * round they were doing usually goes to one other person intact — the customers, their times and
     * their order are a coherent plan, and splitting it up by hand loses that. The snapshots travel
     * with the visits unchanged; they describe the customer, not the clinician.
     *
     * <p>Validated against the <em>target's</em> existing roster, so a reassignment cannot quietly
     * double-book the person taking it on. That is a 400 like any other overlap, not a 409: the
     * conflict is with data the administrator is looking at and can pick a different target for.
     */
    public DutyRoster reassignRound(String rosterId, String toProfessionalId) {
        DutyRoster round = dutyRosterRepository
            .findById(rosterId)
            .orElseThrow(() -> new InvalidRoundException("No such duty roster assignment"));
        if (toProfessionalId == null || toProfessionalId.isBlank()) {
            throw new InvalidRoundException("A target professional is required");
        }
        if (toProfessionalId.equals(round.getProfessionalId())) {
            // Not an error worth failing on, but not a no-op worth pretending about either.
            return round;
        }
        DutyRoster moved = round.professionalId(toProfessionalId);
        validateRound(moved);
        return dutyRosterRepository.save(moved);
    }

    /**
     * Move one visit to another professional (docs/duty-roster.md § 8, DR4).
     *
     * <p><b>The fallback, for when one person cannot take the whole round.</b> The visit lands in the
     * target's round for the same date and shift — an existing one if they already have it, a new one
     * otherwise, copying the source's duty and name so the day view reads sensibly rather than showing
     * an unnamed shift that appeared from nowhere.
     *
     * <p>The target round is validated with the visit in it, so this cannot double-book them either.
     * The source is saved second and only if the target succeeded: there is no transaction across two
     * documents here, and the failure worth avoiding is the one where the visit vanishes from the
     * source without arriving anywhere. Duplicating it is recoverable; losing it is not.
     */
    public DutyRoster reassignVisit(String rosterId, String visitId, String toProfessionalId) {
        DutyRoster source = dutyRosterRepository
            .findById(rosterId)
            .orElseThrow(() -> new InvalidRoundException("No such duty roster assignment"));
        Visit visit = source
            .getVisits()
            .stream()
            .filter(candidate -> visitId != null && visitId.equals(candidate.getId()))
            .findFirst()
            .orElseThrow(() -> new InvalidRoundException("No such visit on this assignment"));
        if (toProfessionalId == null || toProfessionalId.isBlank()) {
            throw new InvalidRoundException("A target professional is required");
        }
        if (toProfessionalId.equals(source.getProfessionalId())) {
            return source;
        }

        DutyRoster target = dutyRosterRepository
            .findRoundsInRange(toProfessionalId, source.getDate(), source.getDate())
            .stream()
            .filter(candidate -> candidate.getShift() == source.getShift())
            .findFirst()
            .orElseGet(
                () ->
                    new DutyRoster()
                        .date(source.getDate())
                        .duty(source.getDuty())
                        .professionalId(toProfessionalId)
                        .shift(source.getShift())
                        .name(source.getName())
                        .description(source.getDescription())
            );
        target.getVisits().add(visit);
        validateRound(target);

        dutyRosterRepository.save(target);
        source.getVisits().removeIf(candidate -> visitId.equals(candidate.getId()));
        dutyRosterRepository.save(source);
        return target;
    }

    /**
     * Give every visit an id, leaving any it already has alone.
     *
     * <p>Ids exist so a single visit can be named for reassignment; they are assigned on write rather
     * than required from the client, because a client inventing them could collide two visits in one
     * round and make the wrong one move.
     */
    private void ensureVisitIds(DutyRoster round) {
        for (Visit visit : round.getVisits()) {
            if (visit.getId() == null || visit.getId().isBlank()) {
                visit.setId(UUID.randomUUID().toString());
            }
        }
    }

    /**
     * Every rule a round must satisfy, in the order that gives the most useful message first.
     *
     * <p>A round with no visits passes everything — ward cover and on-call time are real shifts.
     */
    public void validateRound(DutyRoster round) {
        List<Visit> visits = round.getVisits();
        if (visits.isEmpty()) {
            return;
        }
        List<Interval> intervals = new ArrayList<>();
        for (Visit visit : visits) {
            LocalDateTime start = resolve(round.getDate(), round.getShift(), visit.getStartTime()).orElseThrow(
                () ->
                    new InvalidRoundException(
                        "Visit start %s is outside the %s window on %s".formatted(visit.getStartTime(), round.getShift(), round.getDate())
                    )
            );
            LocalDateTime end = resolve(round.getDate(), round.getShift(), visit.getEndTime()).orElseThrow(
                () ->
                    new InvalidRoundException(
                        "Visit end %s is outside the %s window on %s".formatted(visit.getEndTime(), round.getShift(), round.getDate())
                    )
            );
            if (!end.isAfter(start)) {
                // Also catches a NIGHT visit written backwards, e.g. 01:00->23:30: both times resolve
                // legally but to instants a day apart in the wrong order.
                throw new InvalidRoundException(
                    "Visit end %s must be after its start %s".formatted(visit.getEndTime(), visit.getStartTime())
                );
            }
            intervals.add(new Interval(start, end, visit.getCustomerId()));
        }
        rejectOverlaps(intervals, existingIntervals(round));
    }

    /**
     * Copy name, address and phone from the patient stack onto each visit.
     *
     * <p><b>Failure is silent and the round still saves.</b> {@link PatientServiceClient} degrades to
     * an empty list when the sibling is unreachable, and a round stored with customer ids and no
     * snapshot is far better than a roster an administrator could not write because another stack was
     * down. DR6's read-time refresh fills the gap on the next day-view open.
     *
     * <p>One call for the whole round, not one per visit: {@code /api/profiles} takes no filter — the
     * limit MOB-P2-PRE describes — so this fetches the collection once and indexes it in memory.
     * When that endpoint learns to filter, this becomes a query and not a cache.
     */
    private void populateSnapshots(DutyRoster round) {
        if (round.getVisits().isEmpty()) {
            return;
        }
        Map<String, PatientProfile> byPatientId = new LinkedHashMap<>();
        for (PatientProfile profile : patientServiceClient.profiles()) {
            if (profile.patientId() != null) {
                byPatientId.putIfAbsent(profile.patientId(), profile);
            }
        }
        if (byPatientId.isEmpty()) {
            log.warn("No patient profiles available; storing round {} with customer ids and no snapshot", round.getName());
            return;
        }
        for (Visit visit : round.getVisits()) {
            PatientProfile profile = byPatientId.get(visit.getCustomerId());
            if (profile == null) {
                // Not an error. The customer may be new over there, or the collection may have been
                // truncated by a failure this client is designed to swallow.
                continue;
            }
            visit.setCustomerName(blankToNull(profile.fullName()));
            visit.setCustomerAddress(profile.formattedAddress());
            visit.setCustomerPhone(blankToNull(profile.contactPhone()));
        }
    }

    /**
     * Clear customer snapshots on rounds older than {@link #SNAPSHOT_RETENTION_DAYS}, keeping ids.
     *
     * <p>Idempotent: a second run finds the snapshots already null and reports zero, so the scheduler
     * may run nightly and an operator may run it on demand without doubling anything up. Rounds whose
     * snapshots are already clear are not rewritten at all.
     */
    public PurgeResult purgeExpiredSnapshots() {
        LocalDate cutoff = LocalDate.now().minusDays(SNAPSHOT_RETENTION_DAYS);
        int rounds = 0;
        int cleared = 0;
        for (DutyRoster round : dutyRosterRepository.findByDateLessThan(cutoff)) {
            int clearedHere = 0;
            for (Visit visit : round.getVisits()) {
                if (visit.clearSnapshot()) {
                    clearedHere++;
                }
            }
            if (clearedHere > 0) {
                dutyRosterRepository.save(round);
                rounds++;
                cleared += clearedHere;
            }
        }
        if (rounds > 0) {
            // Ids only — the point of the purge is that these names stop existing here.
            log.info("Purged customer snapshots from {} visit(s) across {} round(s) dated before {}", cleared, rounds, cutoff);
        }
        return new PurgeResult(rounds, cleared);
    }

    // ------------------------------------------------------ window resolution

    /**
     * Place a clock time on a calendar day, or answer empty if it is outside the shift's window.
     *
     * <p>This is the single place the {@code NIGHT} wrap is expressed:
     *
     * <ul>
     *   <li>{@code DAY} 07:00–15:00 and {@code EVENING} 15:00–23:00 sit inside their date.
     *   <li>{@code NIGHT} 23:00–07:00 splits — 23:00 and later is the assignment date, before 07:00 is
     *       <b>the next day</b>. Everything between 07:00 and 23:00 is outside it.
     *   <li>{@code FLEXIBLE} covers the whole assignment date and accepts any time on it.
     * </ul>
     *
     * <p>Bounds are inclusive at both ends here, so a visit may start exactly at the window's open and
     * end exactly at its close; {@link #validateRound} separately requires end to be after start,
     * which is what stops a zero-length visit at the boundary.
     */
    public static Optional<LocalDateTime> resolve(LocalDate date, ShiftType shift, LocalTime time) {
        if (date == null || shift == null || time == null) {
            return Optional.empty();
        }
        return switch (shift) {
            case FLEXIBLE -> Optional.of(date.atTime(time));
            case DAY -> within(date, time, LocalTime.of(7, 0), LocalTime.of(15, 0));
            case EVENING -> within(date, time, LocalTime.of(15, 0), LocalTime.of(23, 0));
            case NIGHT -> {
                if (!time.isBefore(LocalTime.of(23, 0))) {
                    yield Optional.of(date.atTime(time));
                }
                if (!time.isAfter(LocalTime.of(7, 0))) {
                    yield Optional.of(date.plusDays(1).atTime(time));
                }
                yield Optional.empty();
            }
        };
    }

    private static Optional<LocalDateTime> within(LocalDate date, LocalTime time, LocalTime open, LocalTime close) {
        boolean inside = !time.isBefore(open) && !time.isAfter(close);
        return inside ? Optional.of(date.atTime(time)) : Optional.empty();
    }

    // --------------------------------------------------------------- overlap

    private record Interval(LocalDateTime start, LocalDateTime end, String customerId) {
        /**
         * Half-open, so back-to-back calls do not collide.
         *
         * <p>One visit ending at 10:00 and the next starting at 10:00 is a clinician walking next
         * door, which is the normal shape of a round — treating the shared boundary as a clash would
         * reject most real rounds. Travel time between addresses is not modelled at all: the
         * administrator owns feasibility, and a system guessing journey times would be confidently
         * wrong about Accra traffic.
         */
        boolean overlaps(Interval other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }
    }

    /**
     * Every already-stored interval for this professional that could collide with the round.
     *
     * <p>Reads the neighbouring dates too, and must: a {@code NIGHT} round on the 3rd runs into the
     * morning of the 4th, so a 05:00 visit on the 4th's round can clash with it even though the two
     * rounds carry different dates. Checking only the same date is the plausible-looking version of
     * this that silently permits double-booking across midnight.
     *
     * <p>The round being validated is excluded by id, so re-saving one does not collide with itself.
     */
    private List<Interval> existingIntervals(DutyRoster round) {
        List<Interval> intervals = new ArrayList<>();
        List<DutyRoster> neighbours = dutyRosterRepository.findRoundsAround(
            round.getProfessionalId(),
            round.getDate().minusDays(1),
            round.getDate().plusDays(1)
        );
        for (DutyRoster other : neighbours) {
            if (other.getId() != null && other.getId().equals(round.getId())) {
                continue;
            }
            for (Visit visit : other.getVisits()) {
                resolve(other.getDate(), other.getShift(), visit.getStartTime()).ifPresent(
                    start ->
                        resolve(other.getDate(), other.getShift(), visit.getEndTime())
                            .filter(end -> end.isAfter(start))
                            .ifPresent(end -> intervals.add(new Interval(start, end, visit.getCustomerId())))
                );
            }
        }
        return intervals;
    }

    /**
     * Reject a professional being in two places at once — within the round and against stored ones.
     *
     * <p>Both directions matter and they fail differently: a round that overlaps itself is an entry
     * mistake, one that overlaps a stored round is a scheduling conflict the administrator cannot see
     * from the form they are filling in. The message names the times so they can find it.
     */
    private void rejectOverlaps(List<Interval> proposed, List<Interval> existing) {
        List<Interval> sorted = new ArrayList<>(proposed);
        sorted.sort(Comparator.comparing(Interval::start));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).overlaps(sorted.get(i))) {
                throw new InvalidRoundException(
                    "Visits overlap within the round: %s–%s and %s–%s".formatted(
                            sorted.get(i - 1).start(),
                            sorted.get(i - 1).end(),
                            sorted.get(i).start(),
                            sorted.get(i).end()
                        )
                );
            }
        }
        for (Interval candidate : proposed) {
            for (Interval taken : existing) {
                if (candidate.overlaps(taken)) {
                    throw new InvalidRoundException(
                        "Visit %s–%s overlaps an existing assignment %s–%s for this professional".formatted(
                                candidate.start(),
                                candidate.end(),
                                taken.start(),
                                taken.end()
                            )
                    );
                }
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
