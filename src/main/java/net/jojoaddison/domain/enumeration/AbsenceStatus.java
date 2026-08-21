package net.jojoaddison.domain.enumeration;

/**
 * Whether time off has been granted (docs/duty-roster.md § 8, DR4).
 *
 * <p>Two values, deliberately. There is no {@code REJECTED}: an absence the administrator will not
 * grant is deleted, which is also what a professional withdrawing their own request does. A rejected
 * record that lingers on the calendar is a day nobody can read — is it off, or not? — and the
 * conversation about why it was declined does not belong in a status enum.
 *
 * <p>{@code REQUESTED} is visible only to the requester and to roster administrators, and renders
 * hatched rather than solid so a clinician can see that time off is <em>asked for</em>, not granted.
 * Getting that wrong in either direction is a person turning up or not turning up on the strength of
 * a colour.
 */
public enum AbsenceStatus {
    REQUESTED,
    APPROVED,
}
