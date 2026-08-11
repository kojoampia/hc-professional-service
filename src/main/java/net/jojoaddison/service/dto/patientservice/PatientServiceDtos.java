package net.jojoaddison.service.dto.patientservice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The slices of patientservice's documents this service reads.
 *
 * <p>Deliberately partial. Each record names only the fields professionalservice actually uses, and
 * every one is {@code @JsonIgnoreProperties(ignoreUnknown = true)} so the sibling service can add,
 * reorder or remove fields we do not read without breaking this one. Mirroring its documents in full
 * would turn every change over there into a deployment here.
 *
 * <p>Shapes taken from {@code hc-patient/api}'s domain classes rather than guessed: {@code Profile}
 * carries a {@code patientId} alongside its own id, and that — not the profile id — is the key that
 * joins to {@code ClinicalCase.patientId} and to this service's {@code Task.patientId}.
 */
public final class PatientServiceDtos {

    private PatientServiceDtos() {}

    /**
     * A patient's demographics, from {@code GET /api/profiles}.
     *
     * <p>{@code patientId} is the join key. {@code id} is the profile's own identifier and is not
     * used for joining — reading it as the patient id silently produces an empty directory.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PatientProfile(
        String id,
        String patientId,
        String firstName,
        String middleNames,
        String lastName,
        LocalDate birthDate,
        String sex,
        String mobilePhone,
        String phoneNumber,
        String email,
        String contacts
    ) {
        /** Display name, tolerant of the middle names being absent, which they usually are. */
        public String fullName() {
            StringBuilder name = new StringBuilder();
            if (firstName != null && !firstName.isBlank()) {
                name.append(firstName.trim());
            }
            if (middleNames != null && !middleNames.isBlank()) {
                if (!name.isEmpty()) name.append(' ');
                name.append(middleNames.trim());
            }
            if (lastName != null && !lastName.isBlank()) {
                if (!name.isEmpty()) name.append(' ');
                name.append(lastName.trim());
            }
            return name.toString();
        }

        /** Best available number; the mobile is the one clinicians actually call. */
        public String contactPhone() {
            return mobilePhone != null && !mobilePhone.isBlank() ? mobilePhone : phoneNumber;
        }
    }

    /**
     * A clinical case, from {@code GET /api/clinical-cases}.
     *
     * <p>{@code assignedProfessionalId} is one half of "has worked with this patient"; the other is
     * this service's own {@code Task.attendantId}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClinicalCase(
        String id,
        String patientId,
        Instant openedAt,
        Instant closedAt,
        String brief,
        String status,
        String assignedProfessionalId
    ) {}

    /** An entry in the patient's activity log, from {@code GET /api/activity-logs}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityLog(String id, String patientId, String name, String description, Instant createdDate) {}

    /** A medication record, from {@code GET /api/medications}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Medication(String id, String patientId, String name, String description, Instant createdDate) {}

    /** A clinical report, from {@code GET /api/reports}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Report(String id, String patientId, String name, String category, String description, String url, Instant createdDate) {}
}
