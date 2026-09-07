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
 *
 * <p><b>And the matrix has exceptions, which are part of it.</b> Four prefixes sit above the
 * {@code POST /api/**} rule in {@code SecurityConfiguration} — onboarding, messaging, notifications
 * and absences — because a carer who could not answer a message, register a device or ask for time
 * off would be worse than one who got none of those things. The second half of this class holds the
 * messaging exception to its stated width: hoisted above the mutation matrix does <b>not</b> mean
 * open to any authenticated caller, because two of its endpoints are not own-scoped.
 *
 * <p><b>The mutation matrix itself is unchanged by backlog item 30</b>, and the last section says
 * where the angel does move. Angel was already outside {@code CLINICAL_MUTATION} — a rule about
 * clinical writes — and stays outside it; what changed on 2026-09-06 is that it left
 * {@code CLINICAL_AND_ADMIN}, the wider "somebody who works here" set, so the two messaging
 * endpoints that name that array now refuse it. Read-only in v1 and not-a-discipline are two
 * different statements about an angel and this class is where they are both visible.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ClinicalAuthorityMatrixIT {

    private static final String CATEGORY_PAYLOAD = "{\"name\":\"matrix-test\"}";

    /** Well-formed enough to reach the handler: a body and one explicit recipient. */
    private static final String CONVERSATION_PAYLOAD = "{\"body\":\"matrix-test\",\"recipientIds\":[\"matrix-recipient\"]}";

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

    // --- The messaging exception, and how far it goes ------------------------------------------
    //
    // `/api/messaging/**` sits ABOVE the POST /api/** rule, so the mutation matrix never applied to
    // it at all. That hoist is right — correspondence is not clinical data, and a carer who could
    // receive a message and never answer one is the failure it exists to prevent. What was wrong is
    // that it held the WHOLE prefix at .authenticated(), and two endpoints under it are not
    // own-scoped: the recipient directory is the estate's ACTIVE professionals with their LOGINS,
    // and starting a conversation writes into other people's inboxes, role broadcast included.
    // Those two now want a clinical authority; everything else under the prefix stays open to any
    // authenticated caller, which is what keeps the four read-only roles able to correspond.

    /** The hoist survives: a read-only role still composes and still reads the directory. */
    @Test
    @WithMockUser(username = "matrix-carer", authorities = { "ROLE_CARER" })
    void aReadOnlyClinicalRoleCanStillStartAConversation() throws Exception {
        restMockMvc
            .perform(post("/api/messaging/conversations").contentType(MediaType.APPLICATION_JSON).content(CONVERSATION_PAYLOAD))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "matrix-carer", authorities = { "ROLE_CARER" })
    void aReadOnlyClinicalRoleCanStillReadTheRecipientDirectory() throws Exception {
        restMockMvc.perform(get("/api/messaging/recipients")).andExpect(status().isOk());
    }

    /**
     * The estate directory is not open to an applicant.
     *
     * <p>{@code displayName} is the login, so an unauthorised read of this is the valid-login list
     * for the gateway's {@code /api/authenticate} — handed, before this rule, to anything holding
     * {@code ROLE_USER}, which is every applicant here and every caller from the two sibling stacks.
     */
    @Test
    @WithMockUser(username = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCannotReadTheRecipientDirectory() throws Exception {
        restMockMvc.perform(get("/api/messaging/recipients")).andExpect(status().isForbidden());
    }

    /**
     * Nor may an applicant put a message into a clinician's inbox.
     *
     * <p>An applicant is not a correspondent in this domain: nothing addresses one — both
     * {@code MessagingService.recipients} and {@code resolveRole} read ACTIVE applications only —
     * and onboarding correspondence travels as {@code correctionNotes} on the application, rendered
     * on the applicant's own profile tab. So no reply path is owed to them either.
     */
    @Test
    @WithMockUser(username = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCannotStartAConversation() throws Exception {
        restMockMvc
            .perform(post("/api/messaging/conversations").contentType(MediaType.APPLICATION_JSON).content(CONVERSATION_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    /**
     * What an applicant keeps: the own-scoped reads the shell fires on every signed-in page. A 403
     * on either would put a permanent error banner over the applicant's wizard, which is why the
     * gateway carries an island for them — and an island the service then refused would be worse
     * than no island, because the refusal is attributed to the service.
     */
    @Test
    @WithMockUser(username = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantStillReachesTheOwnScopedInboxReads() throws Exception {
        restMockMvc.perform(get("/api/messaging/conversations")).andExpect(status().isOk());
        restMockMvc.perform(get("/api/messaging/unread-count")).andExpect(status().isOk());
    }

    // --- And where an angel now sits, which is not where the other three read-only roles do -------
    //
    // The estate decided on 2026-09-06 that an angel is not a clinical discipline (backlog item 30):
    // their authority is an ACTIVE CareDelegation over ONE named patient, held in hc-patient and
    // re-read per request, so it cannot be spelled as a role. ROLE_ANGEL therefore left
    // CLINICAL_AND_ADMIN, and the two endpoints above that name that array stop admitting it.
    //
    // This is the one place in this class where angel and carer part company. Both are outside the
    // mutation matrix and always were -- `angelCannotMutate` above is unaffected -- but carer is a
    // colleague on the estate and angel is a proxy for one household, so only one of them belongs in
    // the recipient directory. The gateway carries the operative half; these cases hold the service's
    // own layer to the same answer, because the two must not disagree.

    /** An angel is not in the estate's professional directory, which is its list of valid logins. */
    @Test
    @WithMockUser(username = "matrix-angel", authorities = { "ROLE_ANGEL" })
    void anAngelCannotReadTheRecipientDirectory() throws Exception {
        restMockMvc.perform(get("/api/messaging/recipients")).andExpect(status().isForbidden());
    }

    /** Nor may an angel put a message into a clinician's inbox, role broadcast included. */
    @Test
    @WithMockUser(username = "matrix-angel", authorities = { "ROLE_ANGEL" })
    void anAngelCannotStartAConversation() throws Exception {
        restMockMvc
            .perform(post("/api/messaging/conversations").contentType(MediaType.APPLICATION_JSON).content(CONVERSATION_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    /**
     * What an angel keeps, and the reason this is a narrowing rather than a lock-out: everything the
     * {@code .authenticated()} rules cover — their own inbox here, and onboarding, notifications and
     * absences beside it. An angel whose account was reduced to unusable would be a different change
     * from the one that was decided.
     */
    @Test
    @WithMockUser(username = "matrix-angel", authorities = { "ROLE_ANGEL" })
    void anAngelStillReachesTheOwnScopedInboxReads() throws Exception {
        restMockMvc.perform(get("/api/messaging/conversations")).andExpect(status().isOk());
        restMockMvc.perform(get("/api/messaging/unread-count")).andExpect(status().isOk());
    }

    /**
     * And an angel still reads clinical data <em>from this service</em>, which is worth pinning
     * because it is the limit of what item 30 changed here. {@code GET /api/**} is open to any
     * authenticated caller — the mutation matrix is a rule about writes — so the refusal an angel
     * meets on a cross-patient read is the <b>gateway's</b>, at {@code /services/**}, before the
     * request ever arrives. Narrowing this service's read rule to match would also lock out every
     * applicant, which is not what was decided.
     */
    @Test
    @WithMockUser(authorities = { "ROLE_ANGEL" })
    void anAngelStillReadsWhatAnyAuthenticatedCallerReads() throws Exception {
        restMockMvc.perform(get("/api/categories")).andExpect(status().isOk());
    }
}
