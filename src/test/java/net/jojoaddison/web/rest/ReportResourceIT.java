package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Report;
import net.jojoaddison.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link ReportResource} REST controller.
 */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class ReportResourceIT {

    private static final String DEFAULT_CATEGORY = "AAAAAAAAAA";
    private static final String UPDATED_CATEGORY = "BBBBBBBBBB";

    private static final String DEFAULT_DESCRIPTION = "AAAAAAAAAA";
    private static final String UPDATED_DESCRIPTION = "BBBBBBBBBB";

    private static final String DEFAULT_NAME = "AAAAAAAAAA";
    private static final String UPDATED_NAME = "BBBBBBBBBB";

    private static final String DEFAULT_URL = "AAAAAAAAAA";
    private static final String UPDATED_URL = "BBBBBBBBBB";

    private static final String DEFAULT_PROFESSIONAL_ID = "AAAAAAAAAA";
    private static final String UPDATED_PROFESSIONAL_ID = "BBBBBBBBBB";

    private static final LocalDate DEFAULT_CREATED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_CREATED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final LocalDate DEFAULT_MODIFIED_DATE = LocalDate.ofEpochDay(0L);
    private static final LocalDate UPDATED_MODIFIED_DATE = LocalDate.now(ZoneId.systemDefault());

    private static final String DEFAULT_CREATED_BY = "AAAAAAAAAA";
    private static final String UPDATED_CREATED_BY = "BBBBBBBBBB";

    private static final String DEFAULT_MODIFIED_BY = "AAAAAAAAAA";
    private static final String UPDATED_MODIFIED_BY = "BBBBBBBBBB";

    private static final String ENTITY_API_URL = "/api/reports";
    private static final String ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}";
    private static final String ENTITY_SEARCH_API_URL = "/api/reports/_search";

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private MockMvc restReportMockMvc;

    private Report report;

    /**
     * Create an entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Report createEntity() {
        Report report = new Report()
            .category(DEFAULT_CATEGORY)
            .description(DEFAULT_DESCRIPTION)
            .name(DEFAULT_NAME)
            .url(DEFAULT_URL)
            .professionalId(DEFAULT_PROFESSIONAL_ID)
            .createdDate(DEFAULT_CREATED_DATE)
            .modifiedDate(DEFAULT_MODIFIED_DATE)
            .createdBy(DEFAULT_CREATED_BY)
            .modifiedBy(DEFAULT_MODIFIED_BY);
        return report;
    }

    /**
     * Create an updated entity for this test.
     *
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which requires the current entity.
     */
    public static Report createUpdatedEntity() {
        Report report = new Report()
            .category(UPDATED_CATEGORY)
            .description(UPDATED_DESCRIPTION)
            .name(UPDATED_NAME)
            .url(UPDATED_URL)
            .professionalId(UPDATED_PROFESSIONAL_ID)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);
        return report;
    }

    @BeforeEach
    public void initTest() {
        reportRepository.deleteAll();
        report = createEntity();
    }

    @Test
    void createReport() throws Exception {
        int databaseSizeBeforeCreate = reportRepository.findAll().size();
        // Create the Report
        restReportMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(report)))
            .andExpect(status().isCreated());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeCreate + 1);
        Report testReport = reportList.get(reportList.size() - 1);
        assertThat(testReport.getCategory()).isEqualTo(DEFAULT_CATEGORY);
        assertThat(testReport.getDescription()).isEqualTo(DEFAULT_DESCRIPTION);
        assertThat(testReport.getName()).isEqualTo(DEFAULT_NAME);
        assertThat(testReport.getUrl()).isEqualTo(DEFAULT_URL);
        assertThat(testReport.getProfessionalId()).isEqualTo(DEFAULT_PROFESSIONAL_ID);
        assertThat(testReport.getCreatedDate()).isEqualTo(DEFAULT_CREATED_DATE);
        assertThat(testReport.getModifiedDate()).isEqualTo(DEFAULT_MODIFIED_DATE);
        assertThat(testReport.getCreatedBy()).isEqualTo(DEFAULT_CREATED_BY);
        assertThat(testReport.getModifiedBy()).isEqualTo(DEFAULT_MODIFIED_BY);
    }

    @Test
    void createReportWithExistingId() throws Exception {
        // Create the Report with an existing ID
        report.setId("existing_id");

        int databaseSizeBeforeCreate = reportRepository.findAll().size();

        // An entity with an existing ID cannot be created, so this API call must fail
        restReportMockMvc
            .perform(post(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(report)))
            .andExpect(status().isBadRequest());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    void getAllReports() throws Exception {
        // Initialize the database
        reportRepository.save(report);

        // Get all the reportList
        restReportMockMvc
            .perform(get(ENTITY_API_URL + "?sort=id,desc"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(report.getId())))
            .andExpect(jsonPath("$.[*].category").value(hasItem(DEFAULT_CATEGORY)))
            .andExpect(jsonPath("$.[*].description").value(hasItem(DEFAULT_DESCRIPTION)))
            .andExpect(jsonPath("$.[*].name").value(hasItem(DEFAULT_NAME)))
            .andExpect(jsonPath("$.[*].url").value(hasItem(DEFAULT_URL)))
            .andExpect(jsonPath("$.[*].professionalId").value(hasItem(DEFAULT_PROFESSIONAL_ID)))
            .andExpect(jsonPath("$.[*].createdDate").value(hasItem(DEFAULT_CREATED_DATE.toString())))
            .andExpect(jsonPath("$.[*].modifiedDate").value(hasItem(DEFAULT_MODIFIED_DATE.toString())))
            .andExpect(jsonPath("$.[*].createdBy").value(hasItem(DEFAULT_CREATED_BY)))
            .andExpect(jsonPath("$.[*].modifiedBy").value(hasItem(DEFAULT_MODIFIED_BY)));
    }

    @Test
    void getReport() throws Exception {
        // Initialize the database
        reportRepository.save(report);

        // Get the report
        restReportMockMvc
            .perform(get(ENTITY_API_URL_ID, report.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.id").value(report.getId()))
            .andExpect(jsonPath("$.category").value(DEFAULT_CATEGORY))
            .andExpect(jsonPath("$.description").value(DEFAULT_DESCRIPTION))
            .andExpect(jsonPath("$.name").value(DEFAULT_NAME))
            .andExpect(jsonPath("$.url").value(DEFAULT_URL))
            .andExpect(jsonPath("$.professionalId").value(DEFAULT_PROFESSIONAL_ID))
            .andExpect(jsonPath("$.createdDate").value(DEFAULT_CREATED_DATE.toString()))
            .andExpect(jsonPath("$.modifiedDate").value(DEFAULT_MODIFIED_DATE.toString()))
            .andExpect(jsonPath("$.createdBy").value(DEFAULT_CREATED_BY))
            .andExpect(jsonPath("$.modifiedBy").value(DEFAULT_MODIFIED_BY));
    }

    @Test
    void getNonExistingReport() throws Exception {
        // Get the report
        restReportMockMvc.perform(get(ENTITY_API_URL_ID, Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void putExistingReport() throws Exception {
        // Initialize the database
        reportRepository.save(report);

        int databaseSizeBeforeUpdate = reportRepository.findAll().size();

        // Update the report
        Report updatedReport = reportRepository.findById(report.getId()).orElseThrow();
        updatedReport
            .category(UPDATED_CATEGORY)
            .description(UPDATED_DESCRIPTION)
            .name(UPDATED_NAME)
            .url(UPDATED_URL)
            .professionalId(UPDATED_PROFESSIONAL_ID)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);

        restReportMockMvc
            .perform(
                put(ENTITY_API_URL_ID, updatedReport.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(updatedReport))
            )
            .andExpect(status().isOk());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
        Report testReport = reportList.get(reportList.size() - 1);
        assertThat(testReport.getCategory()).isEqualTo(UPDATED_CATEGORY);
        assertThat(testReport.getDescription()).isEqualTo(UPDATED_DESCRIPTION);
        assertThat(testReport.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testReport.getUrl()).isEqualTo(UPDATED_URL);
        assertThat(testReport.getProfessionalId()).isEqualTo(UPDATED_PROFESSIONAL_ID);
        assertThat(testReport.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testReport.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testReport.getCreatedBy()).isEqualTo(UPDATED_CREATED_BY);
        assertThat(testReport.getModifiedBy()).isEqualTo(UPDATED_MODIFIED_BY);
    }

    @Test
    void putNonExistingReport() throws Exception {
        int databaseSizeBeforeUpdate = reportRepository.findAll().size();
        report.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restReportMockMvc
            .perform(
                put(ENTITY_API_URL_ID, report.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(report))
            )
            .andExpect(status().isBadRequest());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithIdMismatchReport() throws Exception {
        int databaseSizeBeforeUpdate = reportRepository.findAll().size();
        report.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restReportMockMvc
            .perform(
                put(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestUtil.convertObjectToJsonBytes(report))
            )
            .andExpect(status().isBadRequest());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void putWithMissingIdPathParamReport() throws Exception {
        int databaseSizeBeforeUpdate = reportRepository.findAll().size();
        report.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restReportMockMvc
            .perform(put(ENTITY_API_URL).contentType(MediaType.APPLICATION_JSON).content(TestUtil.convertObjectToJsonBytes(report)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void partialUpdateReportWithPatch() throws Exception {
        // Initialize the database
        reportRepository.save(report);

        int databaseSizeBeforeUpdate = reportRepository.findAll().size();

        // Update the report using partial update
        Report partialUpdatedReport = new Report();
        partialUpdatedReport.setId(report.getId());

        partialUpdatedReport
            .description(UPDATED_DESCRIPTION)
            .name(UPDATED_NAME)
            .professionalId(UPDATED_PROFESSIONAL_ID)
            .createdDate(UPDATED_CREATED_DATE);

        restReportMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedReport.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedReport))
            )
            .andExpect(status().isOk());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
        Report testReport = reportList.get(reportList.size() - 1);
        assertThat(testReport.getCategory()).isEqualTo(DEFAULT_CATEGORY);
        assertThat(testReport.getDescription()).isEqualTo(UPDATED_DESCRIPTION);
        assertThat(testReport.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testReport.getUrl()).isEqualTo(DEFAULT_URL);
        assertThat(testReport.getProfessionalId()).isEqualTo(UPDATED_PROFESSIONAL_ID);
        assertThat(testReport.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testReport.getModifiedDate()).isEqualTo(DEFAULT_MODIFIED_DATE);
        assertThat(testReport.getCreatedBy()).isEqualTo(DEFAULT_CREATED_BY);
        assertThat(testReport.getModifiedBy()).isEqualTo(DEFAULT_MODIFIED_BY);
    }

    @Test
    void fullUpdateReportWithPatch() throws Exception {
        // Initialize the database
        reportRepository.save(report);

        int databaseSizeBeforeUpdate = reportRepository.findAll().size();

        // Update the report using partial update
        Report partialUpdatedReport = new Report();
        partialUpdatedReport.setId(report.getId());

        partialUpdatedReport
            .category(UPDATED_CATEGORY)
            .description(UPDATED_DESCRIPTION)
            .name(UPDATED_NAME)
            .url(UPDATED_URL)
            .professionalId(UPDATED_PROFESSIONAL_ID)
            .createdDate(UPDATED_CREATED_DATE)
            .modifiedDate(UPDATED_MODIFIED_DATE)
            .createdBy(UPDATED_CREATED_BY)
            .modifiedBy(UPDATED_MODIFIED_BY);

        restReportMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, partialUpdatedReport.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(partialUpdatedReport))
            )
            .andExpect(status().isOk());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
        Report testReport = reportList.get(reportList.size() - 1);
        assertThat(testReport.getCategory()).isEqualTo(UPDATED_CATEGORY);
        assertThat(testReport.getDescription()).isEqualTo(UPDATED_DESCRIPTION);
        assertThat(testReport.getName()).isEqualTo(UPDATED_NAME);
        assertThat(testReport.getUrl()).isEqualTo(UPDATED_URL);
        assertThat(testReport.getProfessionalId()).isEqualTo(UPDATED_PROFESSIONAL_ID);
        assertThat(testReport.getCreatedDate()).isEqualTo(UPDATED_CREATED_DATE);
        assertThat(testReport.getModifiedDate()).isEqualTo(UPDATED_MODIFIED_DATE);
        assertThat(testReport.getCreatedBy()).isEqualTo(UPDATED_CREATED_BY);
        assertThat(testReport.getModifiedBy()).isEqualTo(UPDATED_MODIFIED_BY);
    }

    @Test
    void patchNonExistingReport() throws Exception {
        int databaseSizeBeforeUpdate = reportRepository.findAll().size();
        report.setId(UUID.randomUUID().toString());

        // If the entity doesn't have an ID, it will throw BadRequestAlertException
        restReportMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, report.getId())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(report))
            )
            .andExpect(status().isBadRequest());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithIdMismatchReport() throws Exception {
        int databaseSizeBeforeUpdate = reportRepository.findAll().size();
        report.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restReportMockMvc
            .perform(
                patch(ENTITY_API_URL_ID, UUID.randomUUID().toString())
                    .contentType("application/merge-patch+json")
                    .content(TestUtil.convertObjectToJsonBytes(report))
            )
            .andExpect(status().isBadRequest());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void patchWithMissingIdPathParamReport() throws Exception {
        int databaseSizeBeforeUpdate = reportRepository.findAll().size();
        report.setId(UUID.randomUUID().toString());

        // If url ID doesn't match entity ID, it will throw BadRequestAlertException
        restReportMockMvc
            .perform(patch(ENTITY_API_URL).contentType("application/merge-patch+json").content(TestUtil.convertObjectToJsonBytes(report)))
            .andExpect(status().isMethodNotAllowed());

        // Validate the Report in the database
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeUpdate);
    }

    @Test
    void deleteReport() throws Exception {
        // Initialize the database
        reportRepository.save(report);

        int databaseSizeBeforeDelete = reportRepository.findAll().size();

        // Delete the report
        restReportMockMvc
            .perform(delete(ENTITY_API_URL_ID, report.getId()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Validate the database contains one less item
        List<Report> reportList = reportRepository.findAll();
        assertThat(reportList).hasSize(databaseSizeBeforeDelete - 1);
    }

    @Test
    void searchReport() throws Exception {
        // Initialize the database
        report = reportRepository.save(report);

        // Search the report
        restReportMockMvc
            .perform(get(ENTITY_SEARCH_API_URL + "?query=id:" + report.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(jsonPath("$.[*].id").value(hasItem(report.getId())))
            .andExpect(jsonPath("$.[*].category").value(hasItem(DEFAULT_CATEGORY)))
            .andExpect(jsonPath("$.[*].description").value(hasItem(DEFAULT_DESCRIPTION)))
            .andExpect(jsonPath("$.[*].name").value(hasItem(DEFAULT_NAME)))
            .andExpect(jsonPath("$.[*].url").value(hasItem(DEFAULT_URL)))
            .andExpect(jsonPath("$.[*].professionalId").value(hasItem(DEFAULT_PROFESSIONAL_ID)))
            .andExpect(jsonPath("$.[*].createdDate").value(hasItem(DEFAULT_CREATED_DATE.toString())))
            .andExpect(jsonPath("$.[*].modifiedDate").value(hasItem(DEFAULT_MODIFIED_DATE.toString())))
            .andExpect(jsonPath("$.[*].createdBy").value(hasItem(DEFAULT_CREATED_BY)))
            .andExpect(jsonPath("$.[*].modifiedBy").value(hasItem(DEFAULT_MODIFIED_BY)));
    }
}
