package net.jojoaddison.security;

/**
 * Constants for Spring Security authorities.
 */
public final class AuthoritiesConstants {

    public static final String ADMIN = "ROLE_ADMIN";

    public static final String USER = "ROLE_USER";

    public static final String PATIENT = "ROLE_PATIENT";

    public static final String DOCTOR = "ROLE_DOCTOR";

    public static final String NURSE = "ROLE_NURSE";

    public static final String PARAMEDIC = "ROLE_PARAMEDIC";

    public static final String PHARMACIST = "ROLE_PHARMACIST";

    public static final String THERAPIST = "ROLE_THERAPIST";

    public static final String CARER = "ROLE_CARER";

    public static final String ANGEL = "ROLE_ANGEL";

    public static final String CHEMIST = "ROLE_CHEMIST";

    public static final String TECHNICIAN = "ROLE_TECHNICIAN";

    public static final String ANONYMOUS = "ROLE_ANONYMOUS";

    /**
     * Clinical roles allowed to mutate professional-domain data (onboarding
     * workflow §Authorities): admin/doctor plus the clinical-mutation group.
     * Carer, Angel, Chemist, and Technician are read-only in v1 — keep this
     * aligned with web's CLINICAL_MUTATION_ROLES in authority-role.ts.
     */
    public static final String[] CLINICAL_MUTATION = { ADMIN, DOCTOR, NURSE, PARAMEDIC, PHARMACIST, THERAPIST };

    /**
     * The administrator and all nine clinical authorities — "somebody who works here", as opposed to
     * "somebody who may write clinical data".
     *
     * <p><b>All nine, not the six of {@link #CLINICAL_MUTATION}.</b> Carer, angel, chemist and
     * technician are read-only in v1, which is a rule about clinical <em>writes</em>. Using the six
     * here would take those four out of the recipient directory and out of starting a conversation —
     * a colleague who can receive a message and never open one, which is the exact failure
     * {@code MessagingResource}'s hoist above the mutation matrix exists to prevent.
     *
     * <p><b>{@code ROLE_USER} is deliberately absent.</b> An applicant holds it and nothing else, and
     * so does a caller from either sibling stack: the three gateways share one signing key, this
     * service validates no issuer, and hc-patient grants {@code ROLE_USER} alongside
     * {@code ROLE_PATIENT}. Anything gated on this array is therefore closed to a token this stack
     * did not mint, which is what makes it the second layer behind the gateway's
     * {@code CLINICAL_AND_ADMIN} rule rather than a copy of it. The two arrays hold the same ten
     * names and are deliberately not shared: the gateway decides who reaches this service, this
     * service decides who may act, and one of them may narrow without the other.
     */
    public static final String[] CLINICAL_AND_ADMIN = {
        ADMIN,
        DOCTOR,
        NURSE,
        PARAMEDIC,
        PHARMACIST,
        THERAPIST,
        CARER,
        ANGEL,
        CHEMIST,
        TECHNICIAN,
    };

    private AuthoritiesConstants() {}
}
