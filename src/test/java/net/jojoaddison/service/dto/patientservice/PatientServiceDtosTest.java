package net.jojoaddison.service.dto.patientservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.ActivityLog;
import net.jojoaddison.service.dto.patientservice.PatientServiceDtos.Report;
import org.junit.jupiter.api.Test;

/**
 * These DTOs must deserialize what patientservice actually sends.
 *
 * <p><b>Why this test exists.</b> {@code PatientServiceClient} <em>used to</em> catch every exception
 * and answer with an empty list — deliberately, so a sibling outage degraded the dashboard instead of
 * erroring it. The cost of that design was that a <em>mapping</em> fault was indistinguishable from an
 * empty collection: a DTO that could not parse the sibling's JSON produced exactly the same silence as
 * a patient with no activity. Nothing logged at ERROR, nothing failed a build, and the screen rendered
 * a plausible empty list.
 *
 * <p><b>That stopped being the trade on 2026-09-07 (backlog item 24), and this test matters more
 * rather than less for it.</b> A read that did not happen now raises
 * {@code PatientServiceUnavailableException} — a 503 — and a mapping fault is classified
 * {@code Fault.SCHEMA} and logged at ERROR, precisely because it is the one fault there that does
 * <em>not</em> clear on its own: every later read of that collection fails identically until a DTO
 * here or the sibling's schema changes. So the silence is gone, and what replaced it is worse to
 * discover in production than an outage is — one malformed row now 503s the directory, the dashboard,
 * the patient record and the case queue, persistently, and waiting is the wrong remedy for all four.
 *
 * <p>So the shapes are pinned here, against JSON copied from the sibling's own domain classes
 * ({@code hc-patient/api/.../domain/ActivityLog.java} and {@code Report.java}) rather than from this
 * service's assumptions about them. If patientservice renames a field or changes a date type, this
 * fails in a build here rather than as a 503 on every clinician surface computed from that
 * collection.
 */
class PatientServiceDtosTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * The exact shape {@code GET /api/activity-logs} returns.
     *
     * <p>Note {@code createdDate} is a <b>LocalDate</b> there, not an Instant, and the text fields
     * are {@code summary}/{@code detail} rather than {@code name}/{@code description}.
     */
    private static final String ACTIVITY_LOG_JSON =
        """
        {
          "id": "al-1",
          "patientId": "patient-1",
          "caseId": "case-1",
          "loggedAt": "2026-08-22T09:15:00Z",
          "summary": "Wound dressing changed",
          "detail": "Dry, no exudate. Redressed with hydrocolloid.",
          "kind": "OBSERVATION",
          "source": "CLINICIAN",
          "authorId": "professional-1",
          "createdDate": "2026-08-22",
          "createdBy": "nurse"
        }
        """;

    /** The exact shape {@code GET /api/reports} returns. {@code createdDate} is a LocalDate here too. */
    private static final String REPORT_JSON =
        """
        {
          "id": "rep-1",
          "category": "ASSESSMENT",
          "description": "Initial home assessment",
          "summary": "Mobility improving",
          "name": "assessment-2026-08.pdf",
          "url": "https://files.example/assessment.pdf",
          "patientId": "patient-1",
          "caseId": "case-1",
          "authorId": "professional-1",
          "reportDate": "2026-08-20",
          "createdDate": "2026-08-20",
          "modifiedDate": "2026-08-21",
          "createdBy": "doctor",
          "modifiedBy": "doctor"
        }
        """;

    @Test
    void anActivityLogFromPatientserviceDeserializes() throws Exception {
        ActivityLog log = mapper.readValue(ACTIVITY_LOG_JSON, ActivityLog.class);

        assertThat(log.id()).isEqualTo("al-1");
        assertThat(log.patientId()).isEqualTo("patient-1");
        assertThat(log.summary()).isEqualTo("Wound dressing changed");
        assertThat(log.detail()).contains("hydrocolloid");
    }

    @Test
    void anActivityLogCarriesTextTheRecordCanActuallySHOW() throws Exception {
        // The failure this replaces: the DTO asked for name/description, patientservice sends
        // summary/detail, so every entry mapped to a pair of nulls — a record of blank rows.
        ActivityLog log = mapper.readValue(ACTIVITY_LOG_JSON, ActivityLog.class);

        assertThat(log.summary()).isNotBlank();
        assertThat(log.detail()).isNotBlank();
    }

    @Test
    void aReportFromPatientserviceDeserializes() throws Exception {
        Report report = mapper.readValue(REPORT_JSON, Report.class);

        assertThat(report.id()).isEqualTo("rep-1");
        assertThat(report.name()).isEqualTo("assessment-2026-08.pdf");
        assertThat(report.category()).isEqualTo("ASSESSMENT");
        assertThat(report.url()).isNotBlank();
    }

    /**
     * The one that actually bit.
     *
     * <p>{@code createdDate} is a {@code LocalDate} on both sibling documents. Asking for an
     * {@code Instant} does not yield null — Jackson throws on {@code "2026-08-20"}, the client
     * catches it, and the whole collection comes back empty. One wrong date type silently emptied
     * every activity list and every report list on every patient record.
     */
    @Test
    void aDateOnlyCreatedDateDoesNotBlowUpTheWHOLEcollection() throws Exception {
        assertThat(mapper.readValue(ACTIVITY_LOG_JSON, ActivityLog.class).createdDate()).isNotNull();
        assertThat(mapper.readValue(REPORT_JSON, Report.class).createdDate()).isNotNull();
    }

    @Test
    void unknownFieldsAreToleratedSoTheSiblingCanAddThemFreely() throws Exception {
        String withExtra = ACTIVITY_LOG_JSON.replace("\"id\": \"al-1\",", "\"id\": \"al-1\", \"aFieldAddedNextQuarter\": 42,");

        assertThat(mapper.readValue(withExtra, ActivityLog.class).id()).isEqualTo("al-1");
    }
}
