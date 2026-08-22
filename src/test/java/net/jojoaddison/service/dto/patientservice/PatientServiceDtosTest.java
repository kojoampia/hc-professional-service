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
 * <p><b>Why this test exists.</b> {@code PatientServiceClient} catches every exception and answers
 * with an empty list — deliberately, so a sibling outage degrades the dashboard instead of erroring
 * it. The cost of that design is that a <em>mapping</em> fault is indistinguishable from an empty
 * collection: a DTO that cannot parse the sibling's JSON produces exactly the same silence as a
 * patient with no activity. Nothing logs at ERROR, nothing fails a build, and the screen renders a
 * plausible empty list.
 *
 * <p>So the shapes are pinned here, against JSON copied from the sibling's own domain classes
 * ({@code hc-patient/api/.../domain/ActivityLog.java} and {@code Report.java}) rather than from this
 * service's assumptions about them. If patientservice renames a field or changes a date type, this
 * fails loudly instead of a clinician's record quietly emptying.
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
