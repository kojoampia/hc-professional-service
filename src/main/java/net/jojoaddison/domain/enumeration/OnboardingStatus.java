package net.jojoaddison.domain.enumeration;

/**
 * Professional-application lifecycle states (onboarding workflow § Status
 * model). Account creation/activation precede the application and live in the
 * gateway's User lifecycle. SUSPENDED/EXPIRED/DEACTIVATED may be entered from
 * any post-approval state; transition legality is enforced by the WP3 state
 * machine, never by clients.
 */
public enum OnboardingStatus {
    APPLICATION_STARTED,
    PROFILE_COMPLETED,
    CREDENTIAL_REVIEW,
    RETURNED_FOR_CORRECTION,
    REJECTED,
    APPROVED,
    ORGANIZATION_ASSIGNED,
    AUTHORITY_ASSIGNED,
    ROSTER_CONFIGURED,
    ACTIVE,
    SUSPENDED,
    EXPIRED,
    DEACTIVATED,
}
