package net.jojoaddison.service.dto.patientservice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        String contacts,
        Address address
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

        /** The address as one line, or null if there is none to show. See {@link Address#oneLine()}. */
        public String formattedAddress() {
            return address == null ? null : address.oneLine();
        }
    }

    /**
     * Where a patient lives, from the {@code address} on {@code Profile}.
     *
     * <p>It is a {@code @DBRef} over there, and structured rather than free text on purpose — "a
     * digital address, a town and a region cannot be recovered from '5 Ankobra River Street' once
     * someone has typed it that way", as that field's own javadoc puts it. A {@code @DBRef} may
     * arrive as the referenced document, as an unresolved id, or not at all, so **every field here is
     * optional and the caller must cope with an empty result** rather than assuming a shape.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(
        String id,
        String digitalAddress,
        String streetAddress,
        String areaCode,
        String town,
        String city,
        String district,
        String region,
        String country
    ) {
        /**
         * One line for a clinician who is trying to find a door.
         *
         * <p><b>The digital address leads</b> — GhanaPostGPS is what actually navigates in Accra,
         * where street addressing is inconsistent and a house may have no number at all. Street, then
         * the town or city (whichever is filled; profiles use them interchangeably), then the region.
         * {@code areaCode}, {@code district} and {@code country} are deliberately left out: they add
         * length without helping anyone arrive.
         *
         * <p>Blank segments are dropped rather than rendered as gaps, so a half-filled address reads
         * as a short address instead of a broken one. Returns null when there is nothing at all,
         * which the caller stores as "no snapshot" rather than as an empty string.
         */
        public String oneLine() {
            String locality = isPresent(town) ? town : city;
            String joined = Stream.of(digitalAddress, streetAddress, locality, region)
                .filter(Address::isPresent)
                .map(String::trim)
                .collect(Collectors.joining(", "));
            return joined.isBlank() ? null : joined;
        }

        private static boolean isPresent(String value) {
            return value != null && !value.isBlank();
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
        Integer caseNumber,
        String title,
        Instant openedAt,
        Instant closedAt,
        String brief,
        String status,
        String symptoms,
        String diagnosis,
        String assignedProfessionalId,
        String assignedRosterId,
        /**
         * Set when the case has been retired from the working queue. Archived cases are excluded
         * from the sibling's list unless {@code includeArchived} is asked for, so this is normally
         * null on anything read through here.
         */
        Instant archivedAt
    ) {}

    /**
     * An entry in the patient's activity log, from {@code GET /api/activity-logs}.
     *
     * <p><b>Corrected 2026-08-22.</b> This record previously read {@code name}/{@code description}
     * with an {@code Instant createdDate}. patientservice sends neither: its text lives in
     * {@code summary}/{@code detail}, and {@code createdDate} is a <b>LocalDate</b>. The two text
     * fields simply came back null; the date was worse — Jackson throws on {@code "2026-08-22"} into
     * an {@code Instant}, {@code PatientServiceClient} catches it by design, and the whole collection
     * answered empty. Every patient record showed no activity at all, and nothing logged an error.
     *
     * <p>{@code kind} and {@code source} are enums over there and are taken as strings here on
     * purpose, so adding a new value in patientservice cannot fail deserialization in this service.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityLog(
        String id,
        String patientId,
        String caseId,
        Instant loggedAt,
        String summary,
        String detail,
        String kind,
        String source,
        String authorId,
        LocalDate createdDate
    ) {}

    /**
     * A medication record, from {@code GET /api/medications}.
     *
     * <p>{@code createdDate} is a {@code LocalDate} over there — see {@link ActivityLog} for what
     * asking for an {@code Instant} costs.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Medication(
        String id,
        String patientId,
        String caseId,
        String name,
        String description,
        String dosage,
        String status,
        LocalDate startedOn,
        LocalDate createdDate
    ) {}

    /**
     * A clinical report, from {@code GET /api/reports}.
     *
     * <p>{@code reportDate} is when the report is <em>about</em>; {@code createdDate} is when it was
     * filed. Both are {@code LocalDate} — see {@link ActivityLog}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Report(
        String id,
        String patientId,
        String caseId,
        String name,
        String category,
        String description,
        String summary,
        String url,
        String authorId,
        LocalDate reportDate,
        LocalDate createdDate
    ) {}
}
