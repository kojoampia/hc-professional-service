package net.jojoaddison.service.dto;

import java.util.List;

/**
 * The patient-directory and patient-record contracts, as the dashboard already expects them.
 *
 * <p>Field names are taken from the frontend's {@code patient-api.model.ts} rather than chosen here.
 * That file predates this implementation and its specs are written against these exact names, so a
 * rename in either place is a silent 200-with-nulls rather than a failure.
 */
public final class PatientDtos {

    private PatientDtos() {}

    /** A row in the patient directory. {@code isChild} is derived, never stored. */
    public record PatientListItem(String id, String patientName, String lastActivityAt, String sex, boolean isChild) {}

    public record EmergencyContact(String name, String phone) {}

    /** Shared shape for the dated lists on a record: visitations, medications, activities, reports. */
    public record RecordEntry(String id, String occurredAt, String label) {}

    public record ActivityLogEntry(String id, String occurredAt, String label, String title, String description, String createdAt) {}

    public record ClinicalReport(String id, String occurredAt, String label, String reportType, String url) {}

    public record CaseSummary(String id, String openedAt, String brief, String status) {}

    /**
     * A full patient record, assembled from this service's relation plus five patientservice reads.
     *
     * <p>Any of the lists can be empty because the sibling service was unreachable — the client
     * degrades rather than failing — so an empty list here means "nothing to show", not "nothing
     * exists". That ambiguity is the price of the dashboard rendering at all when patientservice is
     * down, and it is why the client logs what it could not read.
     */
    public record PatientRecord(
        String id,
        String patientName,
        String lastActivityAt,
        String sex,
        boolean isChild,
        String dateOfBirth,
        String phone,
        String email,
        EmergencyContact emergencyContact,
        String avatarUrl,
        List<CaseSummary> cases,
        List<RecordEntry> visitations,
        List<ActivityLogEntry> activities,
        List<RecordEntry> medications,
        List<ClinicalReport> reports
    ) {}

    /**
     * The dashboard figures professionalservice can answer on its own.
     *
     * <p>Case counts are deliberately absent. They derive from ClinicalCase, which patientservice
     * owns and already serves to the browser at {@code /api/clinical-cases}; the dashboard composes
     * those client-side. Returning zeros here would be worse than omitting them — a panel showing
     * "0 urgent" is a clinical statement, and this service is not in a position to make it.
     */
    public record DashboardSummary(long patients, long female, long male, long kids) {}
}
