package net.jojoaddison.domain.enumeration;

/**
 * Why a professional is away (docs/duty-roster.md § 8, DR4).
 *
 * <p>Three values, and the shortness is the point: this is a rostering signal, not a leave-management
 * system. What a roster administrator needs to know is whether the day can be filled and whether the
 * absence was foreseeable. {@code OTHER} absorbs everything else — training, bereavement, jury
 * service — rather than growing a taxonomy nobody maintains.
 *
 * <p>The type carries no entitlement, no accrual and no balance. If leave allowances ever matter they
 * belong in {@code hc-admin}, which already owns shifts and earnings, not here.
 */
public enum AbsenceType {
    HOLIDAY,
    SICK,
    OTHER,
}
