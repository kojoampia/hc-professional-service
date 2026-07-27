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

    private AuthoritiesConstants() {}
}
