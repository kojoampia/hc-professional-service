package net.jojoaddison.domain.enumeration;

/**
 * Duty a roster assignment covers — aligned with the eight onboarding clinical
 * disciplines (professional-onboarding-workflow.md § Duty roster; the JDL's
 * MEDIC/VENDOR/ADMINISTRATOR values were corrected away).
 *
 * <p><b>{@code ANGEL} was a ninth value and is retired</b> (docs/backlog.md item 44, 2026-09-08). A
 * care angel supports one named patient; hc-patient owns the concept, the authority and the whole
 * surface for it, and this subsystem stopped naming it. Rostering one onto a shift was never
 * meaningful — an angel covers no duty anybody schedules — and {@code ROLE_ANGEL} no longer exists
 * here to hold the shift with. Do not reintroduce it: {@code AngelDutyRoleMigration} deletes the rows
 * that carried it, and {@code JhipsterEnumFieldValuesTest} holds {@code .jhipster/DutyRoster.json} to
 * this list so a regeneration cannot bring it back.
 */
public enum DutyRole {
    DOCTOR,
    NURSE,
    PARAMEDIC,
    PHARMACIST,
    THERAPIST,
    CARER,
    CHEMIST,
    TECHNICIAN,
    OTHER,
}
