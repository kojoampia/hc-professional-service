package net.jojoaddison.domain.enumeration;

/**
 * Shift windows for duty-roster assignments. Local-time windows (mirrored by
 * the web shift-label logic): MORNING 06:00–14:00, AFTERNOON 14:00–22:00,
 * NIGHT 22:00–06:00 (wraps past midnight), DAY 08:00–17:00. FLEXIBLE has no
 * fixed window — it stands for individually agreed 2–4 hour time blocks on the
 * assignment date (block details go in the assignment description).
 */
public enum ShiftType {
    MORNING,
    AFTERNOON,
    NIGHT,
    DAY,
    FLEXIBLE,
}
