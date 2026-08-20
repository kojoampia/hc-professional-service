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
 *   <li>{@link #FLEXIBLE} 00:00–24:00 — any time on the assignment date, for individually agreed
 *       2–4 hour blocks. A whole day rather than the 00:00–23:00 first proposed, which would have
 *       left an hour-shaped hole before midnight that no rule enforces and nobody would expect.
 * </ul>
 *
 * <p><b>MORNING and AFTERNOON were retired in DR1</b> and existing rows migrated by nearest window
 * (MORNING → DAY, AFTERNOON → EVENING; see {@code ShiftTypeMigration}). Do not reintroduce them:
 * the four values above cover the day without overlap, which is what lets the week grid be a fixed
 * set of rows.
 *
 * <p>This enum is a <b>cross-repo invariant</b>. It is mirrored by the web shift-label logic that
 * drives the sidebar user card and by four i18n catalogues; change it here and all of them move in
 * the same commit, or three of the four go stale with nothing failing to build.
 */
public enum ShiftType {
    DAY,
    EVENING,
    NIGHT,
    FLEXIBLE,
}
