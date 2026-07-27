package net.jojoaddison.web.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP1 gate (professional-onboarding-workflow.md §Authorities): the server —
 * not the frontend — enforces the clinical mutation matrix. Reads are open to
 * every authenticated role; mutations require admin/doctor or the
 * clinical-mutation group (nurse, paramedic, pharmacist, therapist). Carer,
 * Angel, Chemist, and Technician are read-only in v1.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ClinicalAuthorityMatrixIT {

    private static final String CATEGORY_PAYLOAD = "{\"name\":\"matrix-test\"}";

    @Autowired
    private MockMvc restMockMvc;

    @Test
    @WithMockUser(authorities = { "ROLE_CARER" })
    void readOnlyRoleCanRead() throws Exception {
        restMockMvc.perform(get("/api/categories")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = { "ROLE_CARER" })
    void carerCannotMutate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = { "ROLE_ANGEL" })
    void angelCannotMutate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = { "ROLE_CHEMIST" })
    void chemistCannotMutate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = { "ROLE_TECHNICIAN" })
    void technicianCannotMutate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = { "ROLE_NURSE" })
    void mutationRoleCanCreate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(authorities = { "ROLE_DOCTOR" })
    void doctorCanCreate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isCreated());
    }
}
