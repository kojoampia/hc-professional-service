package net.jojoaddison.service.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import net.jojoaddison.domain.DutyRoster;
import net.jojoaddison.domain.Visit;
import net.jojoaddison.domain.enumeration.DutyRole;
import net.jojoaddison.domain.enumeration.ShiftType;

/**
 * The duty roster <b>without the customer snapshot</b> (docs/duty-roster.md § 6).
 *
 * <p>DR2 put personal data into professionalservice for the first time — names, street addresses and
 * phone numbers, on {@link Visit}. § 6 confined it to the day view, and three comments in this repo
 * said so, but nothing enforced it: {@code GET /api/duty-roster} and {@code GET /api/duty-roster/all}
 * both serialised the stored {@link DutyRoster}, so every snapshot on the round went out with it. The
 * range read is the one the dashboard makes on every load, and with no {@code from}/{@code to} it
 * returns the whole roster — so the leak was the common case rather than an edge of it.
 *
 * <p><b>This is the projection those two reads go through now, and it is a separate type rather than
 * a Jackson view or an annotation on the entity for one reason: the day view genuinely needs the
 * snapshot.</b> A rule expressed as "hide these three fields, except here" is one `@JsonIgnore` away
 * from being wrong in either direction, and the direction it fails in silently is the disclosing one.
 * A type that has no place to put a name cannot leak one, whatever the serialiser is later configured
 * to do.
 *
 * <p><b>What is deliberately kept.</b> {@code customerId} stays, and the times and the visit id with
 * it. That is the same line {@link Visit#clearSnapshot()} draws and the same one the 90-day purge
 * draws — identifiers and times are what the calendar counts and what reassignment names, and the
 * domain-event rule this repo already follows is "payloads carry identifiers only"
 * (professional-onboarding-workflow.md § Domain events), not "no patient reference at all".
 *
 * <p>The audit fields go too, as a side effect rather than a goal: a projection carries what its
 * readers use, and no client reads {@code createdBy} off a roster row.
 *
 * <p>{@code DutyRosterVisitPrivacyIT} asserts the absence of the three fields on both reads, by
 * name and against the response body. Assert absence rather than presence if you extend it — a
 * serialiser change that put a field back would pass every positive assertion unchanged.
 */
public final class DutyRosterDtos {

    private DutyRosterDtos() {}

    /**
     * One visit, as a read that is not the day view may see it.
     *
     * <p>Field names match {@link Visit}'s so the frontend's {@code VisitDto} covers both shapes; what
     * differs is that three of them are not here at all. Its own docstring already describes the
     * snapshot as optional and absent from the range read, so this narrows the contract to what that
     * file has always said rather than changing it.
     */
    public record RoundVisit(String id, String customerId, LocalTime startTime, LocalTime endTime) {
        static RoundVisit of(Visit visit) {
            return new RoundVisit(visit.getId(), visit.getCustomerId(), visit.getStartTime(), visit.getEndTime());
        }
    }

    /** One round: the assignment, and its visits reduced to identifiers and times. */
    public record Round(
        String id,
        LocalDate date,
        DutyRole duty,
        String professionalId,
        ShiftType shift,
        String name,
        String description,
        List<RoundVisit> visits
    ) {
        public static Round of(DutyRoster round) {
            return new Round(
                round.getId(),
                round.getDate(),
                round.getDuty(),
                round.getProfessionalId(),
                round.getShift(),
                round.getName(),
                round.getDescription(),
                round.getVisits().stream().map(RoundVisit::of).toList()
            );
        }
    }

    /** Project a whole read. Ordering is the repository's and is preserved. */
    public static List<Round> rounds(List<DutyRoster> rounds) {
        return rounds.stream().map(Round::of).toList();
    }
}
