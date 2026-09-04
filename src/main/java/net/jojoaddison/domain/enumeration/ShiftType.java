package net.jojoaddison.domain.enumeration;

/**
 * Shift windows for duty-roster assignments (docs/duty-roster.md § 2, DR1).
 *
 * <p>Local-time windows, contiguous across the 24 hours:
 *
 * <ul>
 *   <li>{@link #DAY} 07:00–15:00
 *   <li>{@link #EVENING} 15:00–23:00
 *   <li>{@link #NIGHT} 23:00 on the assignment date until 07:00 the next — <b>it wraps</b>, so a
 *       01:00 visit belongs to the <em>previous</em> date's shift. Every consumer has to honour
 *       that: the visit-time validator, the week grid and the day summary alike.
 *   <li>{@link #OFF} <b>no window</b> — a rostered rest day. Planned, and deliberately not worked.
 *   <li>{@link #FLEXIBLE} 00:00–24:00 — any time on the assignment date, for individually agreed
 *       2–4 hour blocks. A whole day rather than the 00:00–23:00 first proposed, which would have
 *       left an hour-shaped hole before midnight that no rule enforces and nobody would expect.
 * </ul>
 *
 * <p><b>{@code OFF} was added on 2026-09-04, and the estate now has one shift vocabulary.</b> This
 * enum and hc-admin's held four values each and differed by one at either end — hc-admin had
 * {@code OFF} and no {@code FLEXIBLE}, this one the reverse — with the other three named
 * identically. The alternative to the superset was a translation table at that boundary, and it was
 * rejected because <b>near-identity is more dangerous than clean difference</b>: three matching
 * names invite every reader, and the author of every new call site, to assume the fourth matches
 * too. Both sides now declare {@code DAY, EVENING, NIGHT, OFF, FLEXIBLE}, in that order. See
 * {@code adminservice-earnings-contract.md}.
 *
 * <p><b>{@code OFF} is the one value that carries no visits, and the server enforces that.</b> A
 * rest day has no rounds, so {@code DutyRosterService.validateRound} rejects an {@code OFF} round
 * holding any {@code visits[]}, and {@code resolve} answers empty for it — unlike {@code FLEXIBLE},
 * whose "no window" means "any time on the date" rather than "no time at all". Two values with no
 * window and only one of them accepting visits is the distinction to hold on to when reading any
 * caller that tests for a missing window.
 *
 * <p><b>MORNING and AFTERNOON were retired in DR1</b> and existing rows migrated by nearest window
 * (MORNING → DAY, AFTERNOON → EVENING; see {@code ShiftTypeMigration}). Do not reintroduce them:
 * the three windowed values above cover the day without overlap, which is what lets the week grid be
 * a fixed set of rows.
 *
 * <p>This enum is a <b>cross-repo invariant</b>, and it now has six mirrors rather than three. It is
 * mirrored by {@code .jhipster/DutyRoster.json}, by the web shift-label logic that drives the
 * sidebar user card, by four web i18n catalogues, by {@code mobile/}'s own copy of the union and its
 * windows, by four mobile catalogues, and by hc-admin's {@code ShiftType} and console. Change it
 * here and all of them move in the same change, or most go stale with nothing failing to build.
 * {@code JhipsterEnumFieldValuesTest} covers the generator input, and a {@code shift-names.spec.ts}
 * in each of {@code web/} and {@code mobile/} covers those catalogues; the link across to hc-admin
 * is made by hand.
 */
public enum ShiftType {
    DAY,
    EVENING,
    NIGHT,
    OFF,
    FLEXIBLE,
}
