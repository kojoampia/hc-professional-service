package net.jojoaddison.web.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WP1 gate (professional-onboarding-workflow.md §Authorities): the server —
 * not the frontend — enforces the clinical mutation matrix. Reads are open to
 * every authenticated role <b>except on {@code /api/profiles}</b> (see below);
 * mutations require admin/doctor or the
 * clinical-mutation group (nurse, paramedic, pharmacist, therapist). Carer,
 * Chemist, and Technician are read-only in v1.
 *
 * <p><b>THE READ RULE HAS AN EXCEPTION NOW, AND THIS SENTENCE USED TO DENY IT.</b> Until backlog item
 * 143 the opening paragraph said reads were open to every authenticated role full stop, while the
 * cases 150 lines down asserted the opposite — the contradiction resolved in the reader's favour only
 * if they read to the end. {@code GET} <i>and</i> {@code HEAD} on {@code /api/profiles} and everything
 * under it require {@code ROLE_ADMIN}, because every one of those reads names its subject in the path,
 * and identity cannot gate a read whose subject is whoever you ask for. A clinician's own profile is
 * not reached that way and never was: {@code GET /api/onboarding/profile} takes no subject at all and
 * stays open to any authenticated caller. {@code ProfileResourceIT} holds the endpoint's own cases;
 * what this class holds is the <i>matrix</i> view — that the refusal applies to a clinician and to a
 * role-less applicant alike.
 *
 * <p><b>And the matrix has exceptions, which are part of it.</b> Four prefixes sit above the
 * {@code POST /api/**} rule in {@code SecurityConfiguration} — onboarding, messaging, notifications
 * and absences — because a carer who could not answer a message, register a device or ask for time
 * off would be worse than one who got none of those things. The second half of this class holds the
 * messaging exception to its stated width: hoisted above the mutation matrix does <b>not</b> mean
 * open to any authenticated caller, because two of its endpoints are not own-scoped.
 *
 * <p><b>The mutation matrix itself is unchanged by backlog items 30 and 44</b>, and the last section
 * says what did move. {@code ROLE_ANGEL} was always outside {@code CLINICAL_MUTATION} — a rule about
 * clinical writes; on 2026-09-06 it left {@code CLINICAL_AND_ADMIN}, the wider "somebody who works
 * here" set (item 30); and on 2026-09-08 it left this subsystem altogether (item 44), because an angel
 * supports one named patient and hc-patient owns the concept. Read-only in v1, not-a-discipline and
 * not-ours-at-all are three different statements and this class is where the last two are visible.
 *
 * <p><b>The last section survives the removal on purpose, and its cases spell the literal.</b> A token
 * bearing {@code ROLE_ANGEL} still arrives here however thoroughly this repository forgets the word —
 * the three gateways share one signing key and this service validates no issuer, so hc-patient's
 * tokens are accepted as readily as ours, and an account on a long-lived database may hold a grant
 * made before the removal. What those cases assert is the runtime fact that has to stay true, which no
 * amount of deleting constants can establish.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ClinicalAuthorityMatrixIT {

    private static final String CATEGORY_PAYLOAD = "{\"name\":\"matrix-test\"}";

    /** Well-formed enough to reach the handler: a body and one explicit recipient. */
    private static final String CONVERSATION_PAYLOAD = "{\"body\":\"matrix-test\",\"recipientIds\":[\"matrix-recipient\"]}";

    @Autowired
    private MockMvc restMockMvc;

    /**
     * Only so {@link #cleanup} can remove the row {@link #anApplicantCanWriteTheirOwnProfile} creates.
     *
     * <p>That case is the one assertion in this class that <em>writes</em> — step 2 creates the
     * caller's profile, so a 200 means a document. Left behind it would be a row with a unique
     * {@code accountId} index on it, visible to every later class in the run; this suite's own rule
     * is to remove what it created rather than to tidy broadly, so the delete is scoped to the one
     * account the case uses.
     */
    @Autowired
    private ProfileRepository profileRepository;

    @AfterEach
    void cleanup() {
        profileRepository
            .findByAccountId(WithMockGatewayUser.Factory.accountIdFor("matrix-applicant"))
            .ifPresent(profileRepository::delete);
    }

    @Test
    @WithMockGatewayUser(authorities = { "ROLE_CARER" })
    void readOnlyRoleCanRead() throws Exception {
        restMockMvc.perform(get("/api/categories")).andExpect(status().isOk());
    }

    @Test
    @WithMockGatewayUser(authorities = { "ROLE_CARER" })
    void carerCannotMutate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockGatewayUser(authorities = { "ROLE_ANGEL" })
    void aTokenBearingTheCareAngelAuthorityCannotMutate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockGatewayUser(authorities = { "ROLE_CHEMIST" })
    void chemistCannotMutate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockGatewayUser(authorities = { "ROLE_TECHNICIAN" })
    void technicianCannotMutate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockGatewayUser(authorities = { "ROLE_NURSE" })
    void mutationRoleCanCreate() throws Exception {
        restMockMvc
            .perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(CATEGORY_PAYLOAD))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockGatewayUser(authorities = { "ROLE_DOCTOR" })
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
    @WithMockGatewayUser(login = "matrix-carer", authorities = { "ROLE_CARER" })
    void aReadOnlyClinicalRoleCanStillStartAConversation() throws Exception {
        restMockMvc
            .perform(post("/api/messaging/conversations").contentType(MediaType.APPLICATION_JSON).content(CONVERSATION_PAYLOAD))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockGatewayUser(login = "matrix-carer", authorities = { "ROLE_CARER" })
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
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
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
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
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
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantStillReachesTheOwnScopedInboxReads() throws Exception {
        restMockMvc.perform(get("/api/messaging/conversations")).andExpect(status().isOk());
        restMockMvc.perform(get("/api/messaging/unread-count")).andExpect(status().isOk());
    }

    // --- Profiles: the reads that name their subject, and the one that cannot -------------------
    //
    // `GET /api/**` is open to any authenticated caller because a read is not a clinical mutation,
    // and for every other resource that is right. `/api/profiles` is the exception, and the reason
    // generalises past it: EVERY READ THERE TAKES ITS SUBJECT FROM THE PATH — or, for the
    // collection, returns every subject there is — so authentication constrains nothing at all.
    // Every caller is authenticated as somebody; the subject is whoever they ask for. Measured on
    // the quality stack on 2026-09-17, a `carer` read a doctor's 21 fields through
    // /account/{accountId}: cardNumber, birthDate, address, emergencyContact, mobilePhone, email,
    // sex. The three gateways share one signing key and not a user store, so the caller could as
    // easily have been an hc-patient account, and hc-admin dials this service over infranet where
    // the gateway's own CLINICAL_AND_ADMIN rule on /services/** never runs. docs/backlog.md item 143.
    //
    // WHAT A CLINICIAN KEEPS is the last case in this section, and it is the half that makes the
    // gate honest rather than merely strict: GET /api/onboarding/profile resolves the caller from
    // the uid claim and takes no subject, so it cannot name anyone else. Identity is the boundary
    // there, which is exactly why it stays .authenticated(). Do not answer "a clinician needs their
    // own profile" by excusing the caller on a subject-addressed path — that shape has to compare
    // caller against path, and it is the one that gets it wrong.

    /** The contract read hc-admin is being pointed at (item 140), and the gate it now meets. */
    @Test
    @WithMockGatewayUser(login = "matrix-admin", authorities = { "ROLE_ADMIN" })
    void anAdministratorReadsAProfile() throws Exception {
        restMockMvc.perform(get("/api/profiles")).andExpect(status().isOk());
    }

    /**
     * A doctor — the widest clinical role, and one of the six {@code CLINICAL_MUTATION} may-write
     * authorities — is refused. If the widest is refused, the read-only three are refused by the
     * same rule and for the same reason.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-doctor", authorities = { "ROLE_DOCTOR" })
    void aClinicianCannotReadAColleaguesProfile() throws Exception {
        restMockMvc.perform(get("/api/profiles")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/profiles/{id}", "matrix-any-id")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/profiles/account/{accountId}", "matrix-any-account")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/profiles/email/{email}", "matrix@example.com")).andExpect(status().isForbidden());
    }

    /** The role that was measured doing it. */
    @Test
    @WithMockGatewayUser(login = "matrix-carer", authorities = { "ROLE_CARER" })
    void aReadOnlyClinicalRoleCannotReadAColleaguesProfile() throws Exception {
        restMockMvc.perform(get("/api/profiles/account/{accountId}", "matrix-any-account")).andExpect(status().isForbidden());
    }

    /**
     * And nor may a role-less applicant — which is also what a sibling stack's patient and a token
     * still bearing {@code ROLE_ANGEL} amount to here, since every positive list refuses them.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCannotReadAProfile() throws Exception {
        restMockMvc.perform(get("/api/profiles")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/profiles/account/{accountId}", "matrix-any-account")).andExpect(status().isForbidden());
    }

    /**
     * <b>What the gate did not take away.</b> A clinician still reads their own profile, through the
     * endpoint that cannot name anyone else — which is where {@code mobile/}'s
     * {@code profile-api.service.ts} has always read it from, and {@code web/} calls
     * {@code ProfileResource} nowhere at all.
     *
     * <p>A clinician who has not completed onboarding has no profile row yet and gets a 404 from it;
     * that is the pre-existing behaviour of {@code OnboardingService.getOwnProfile} and not an
     * authorization answer. What this asserts is the only thing item 143 could have broken and did
     * not: <b>not a 403</b>. Seeding a row to make it a 200 would assert the onboarding write path
     * instead, which {@code ProfileResourceIT} already covers.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-doctor", authorities = { "ROLE_DOCTOR" })
    void aClinicianStillReadsTheirOwnProfileThroughTheEndpointThatCannotNameAnyoneElse() throws Exception {
        restMockMvc.perform(get("/api/onboarding/profile")).andExpect(status().isNotFound());
    }

    // --- /api/profile (singular): the read and write that cannot name anybody else ---------------
    //
    // profile.md's step 2 is written by an APPLICANT, who holds ROLE_USER and nothing else until an
    // administrator assigns one. So the gate is .authenticated() and the reason it is correct here is
    // the reason .authenticated() was a defect on /api/profiles: THE SUBJECT DECIDES THE GATE, NOT
    // THE RESOURCE. /api/profile takes no subject — the account comes from the uid claim — so there
    // is nobody it could disclose but the caller, and an authority check would constrain nothing
    // while the admin gate would lock out the people it exists for.
    //
    // TWO SECURITY RULES MAKE THIS WORK AND BOTH ARE NEW (profile.md T0): the service's
    // `/api/profile` -> .authenticated(), without which the PUT falls to
    // `PUT /api/** -> CLINICAL_MUTATION` and the applicant is 403'd on their own profile; and the
    // gateway's mirrored `/services/professionalservice/api/profile`, which
    // ServicesRouteAuthorizationIT holds. These cases are the service's half.

    /**
     * An applicant reaches their own profile. <b>Not a 403</b> is the whole assertion — the 404 is
     * {@code OwnProfileResource}'s answer for a caller who has not completed step 2 yet, which is
     * most of an applicant's time on the wizard, and not an authorization answer.
     *
     * <p>Seeding a row to make it a 200 would assert the write path instead, which
     * {@code OwnProfileResourceIT} covers.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantReachesTheirOwnProfile() throws Exception {
        restMockMvc.perform(get("/api/profile")).andExpect(status().isNotFound());
    }

    /**
     * And a {@code HEAD} of it, which is the verb a {@code GET}-scoped rule drops.
     *
     * <p>Spring MVC dispatches a {@code HEAD} to the {@code @GetMapping} handler, so a filter-chain
     * rule written with {@code HttpMethod.GET} would let this fall through to whatever sits below —
     * the fail-open caught in review on {@code /api/profiles} (item 143). Here the matcher is
     * method-agnostic, and this case is what fails if somebody narrows it: the answer must be the
     * handler's 404 and not the mutation matrix's 403.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantsHeadOfTheirOwnProfileIsNotRefused() throws Exception {
        restMockMvc.perform(head("/api/profile")).andExpect(status().isNotFound());
    }

    /**
     * An applicant <b>writes</b> their own profile, which is the half the mutation matrix would
     * otherwise refuse.
     *
     * <p>This is the case T0 exists for and the one most easily lost: {@code ROLE_USER} is outside
     * {@code CLINICAL_MUTATION}, so without {@code /api/profile} sitting above
     * {@code PUT /api/** -> CLINICAL_MUTATION} every applicant is 403'd on step 2 — while the
     * {@code GET} above keeps working on {@code /api/** -> .authenticated()}. That asymmetry is the
     * trap: a half-working endpoint reads as a broken save rather than as a missing rule.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCanWriteTheirOwnProfile() throws Exception {
        restMockMvc
            .perform(put("/api/profile").contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Appli\"}"))
            .andExpect(status().isOk());
    }

    /**
     * ⚠ <b>The singular rule does not open the plural admin surface.</b>
     *
     * <p>The two rules live in the same chain and {@code /api/profile} is a prefix of
     * {@code /api/profiles} as a string, so "does one match the other" is a question about Spring's
     * pattern matching that no amount of reading the configuration settles. Asserted in both
     * directions: the applicant above reaches the singular path, and the same applicant is still
     * refused the clinician directory here.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void theSingularOwnProfilePathDoesNotOpenThePluralAdminReads() throws Exception {
        restMockMvc.perform(get("/api/profiles")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/profiles/account/{accountId}", "matrix-any-account")).andExpect(status().isForbidden());
        restMockMvc.perform(head("/api/profiles")).andExpect(status().isForbidden());
    }

    /**
     * And the converse: a clinician refused the plural reads still reaches the singular one.
     *
     * <p>A doctor is the widest clinical role and is refused {@code /api/profiles} by item 143. If the
     * admin gate had been written one character wider it would catch {@code /api/profile} too, and
     * the symptom would be every clinician locked out of their own profile — with
     * {@link #aClinicianCannotReadAColleaguesProfile} still green, because that test asserts the
     * refusal it would have widened.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-doctor", authorities = { "ROLE_DOCTOR" })
    void aClinicianRefusedTheDirectoryStillReachesTheirOwnProfile() throws Exception {
        restMockMvc.perform(get("/api/profiles")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/profile")).andExpect(status().isNotFound());
    }

    // --- /api/personal-document (singular): the applicant's own credentials, T2's T0 rules ---------
    //
    // Same island and same argument as /api/profile above, one step further along the wizard:
    // profile.md's step 3 is uploaded by an APPLICANT holding ROLE_USER and nothing else.
    // OwnPersonalDocumentResource takes no subject on either collection mapping — profileId is
    // derived from the caller's own profile through the uid claim — so .authenticated() is the gate.
    //
    // THE RULE HERE IS A PREFIX AND /api/profile's IS NOT, which is the one real difference: this
    // path has a sub-resource, /{id}/content, the only route by which document bytes leave the
    // service. A prefix is a widening, so the cases below assert where it STOPS as carefully as they
    // assert what it admits — above all that it does not reach /api/personal-documents, PLURAL, whose
    // three GETs return `data` inline with no ownership check at all (profile-addendum.md S1).

    /**
     * An applicant lists their own documents. <b>Not a 403</b> is the assertion; the 400 is
     * {@code ownProfile}'s answer for a caller who has not completed step 2, which is step 3's
     * dependency on step 2 and not an authorization answer.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantReachesTheirOwnDocuments() throws Exception {
        restMockMvc.perform(get("/api/personal-document")).andExpect(status().isBadRequest());
    }

    /** And a {@code HEAD} of it — the verb a {@code GET}-scoped rule drops onto the matrix below. */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantsHeadOfTheirOwnDocumentsIsNotRefused() throws Exception {
        restMockMvc.perform(head("/api/personal-document")).andExpect(status().isBadRequest());
    }

    /**
     * An applicant <b>uploads</b>, which is the half the mutation matrix would otherwise refuse.
     *
     * <p>This is the case T0 exists for on this path and the one most easily lost: {@code ROLE_USER}
     * is outside {@code CLINICAL_MUTATION}, so without {@code /api/personal-document} sitting above
     * {@code POST /api/** -> CLINICAL_MUTATION} every applicant is 403'd uploading their own licence
     * — while the list {@code GET} keeps working on {@code /api/** -> .authenticated()}. That
     * asymmetry reads as a broken upload rather than as a missing rule.
     *
     * <p>The 400 is {@code ownProfile}'s, reached <em>because</em> authorization let the request
     * through; the point is that it is not a 403.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCanPostTheirOwnDocument() throws Exception {
        restMockMvc
            .perform(post("/api/personal-document").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CERTIFICATE\"}"))
            .andExpect(status().isBadRequest());
    }

    /** The sub-resource the prefix exists for: a read of bytes reaches the handler's own 404. */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantReachesTheDocumentContentSubResource() throws Exception {
        restMockMvc.perform(get("/api/personal-document/{id}/content", "matrix-no-such-document")).andExpect(status().isNotFound());
    }

    /**
     * ⛔ <b>The prefix stops at the singular path and does not open the plural CRUD surface.</b>
     *
     * <p>{@code /api/personal-document} is a prefix of {@code /api/personal-documents} as a string, so
     * "does one match the other" is a question about Spring's pattern matching that no amount of
     * reading the configuration settles — and the stakes are higher here than on
     * {@code /api/profile}, because {@code PersonalDocumentResource}'s writes are the only thing
     * keeping a role-less account off a surface that edits and deletes any clinician's documents.
     *
     * <p>⚠ <b>Asserted on the WRITES, not on the reads, and that is deliberate.</b> The plural
     * {@code GET}s already answer a role-less caller today — they carry no {@code @PreAuthorize} and
     * fall to {@code /api/** -> .authenticated()}, which is standing defect S1 and is not this task's
     * to close. So a {@code GET} here could not tell a widened matcher from the existing hole. The
     * mutation matrix is the discriminator: if the new rule reached the plural path these would be
     * admitted to the handler instead of refused.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void theSingularDocumentPathDoesNotOpenThePluralCrudSurface() throws Exception {
        restMockMvc
            .perform(post("/api/personal-documents").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        restMockMvc
            .perform(put("/api/personal-documents/{id}", "matrix-any-document").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        restMockMvc.perform(delete("/api/personal-documents/{id}", "matrix-any-document")).andExpect(status().isForbidden());
    }

    /**
     * And the same for the read-only disciplines: hoisting the singular path above the matrix must not
     * have given a carer a write anywhere.
     */
    @Test
    @WithMockGatewayUser(authorities = { "ROLE_CARER" })
    void aReadOnlyDisciplineStillCannotWriteThePluralDocumentSurface() throws Exception {
        restMockMvc
            .perform(post("/api/personal-documents").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    // --- And where a ROLE_ANGEL token sits, which is not where the read-only disciplines do -------
    //
    // ROLE_ANGEL IS NOT AN AUTHORITY OF THIS SUBSYSTEM. The estate decided on 2026-09-06 that an angel
    // is not a clinical discipline (item 30) -- their authority is an ACTIVE CareDelegation over ONE
    // named patient, held in hc-patient and re-read per request, so it cannot be spelled as a role --
    // and on 2026-09-08 the concept left this stack entirely (item 44). Nothing here seeds, grants or
    // names it.
    //
    // These cases therefore describe a caller, not a role we have: a token minted by hc-patient, or one
    // for an account granted the authority before the removal. Both keep arriving, and the answer must
    // keep being nothing. Both messaging endpoints above name CLINICAL_AND_ADMIN, which is a positive
    // list, so an authority nobody named is an authority nobody admits -- the same reason ROLE_USER is
    // refused. The gateway carries the operative half; these hold the service's own layer to the same
    // answer, because the two must not disagree.

    /** Not in the estate's professional directory, which is its list of valid logins. */
    @Test
    @WithMockGatewayUser(login = "matrix-angel", authorities = { "ROLE_ANGEL" })
    void aTokenBearingTheCareAngelAuthorityCannotReadTheRecipientDirectory() throws Exception {
        restMockMvc.perform(get("/api/messaging/recipients")).andExpect(status().isForbidden());
    }

    /** Nor may it put a message into a clinician's inbox, role broadcast included. */
    @Test
    @WithMockGatewayUser(login = "matrix-angel", authorities = { "ROLE_ANGEL" })
    void aTokenBearingTheCareAngelAuthorityCannotStartAConversation() throws Exception {
        restMockMvc
            .perform(post("/api/messaging/conversations").contentType(MediaType.APPLICATION_JSON).content(CONVERSATION_PAYLOAD))
            .andExpect(status().isForbidden());
    }

    /**
     * What such a caller keeps, and the answer to "what happens to an account that still holds
     * {@code ROLE_ANGEL}": everything the {@code .authenticated()} rules cover — its own inbox here,
     * and onboarding, notifications and absences beside it. <b>It is a role-less applicant</b>, no more
     * and no less, because an authority nothing here names carries nothing here. An account reduced to
     * unusable would be a different change from the one that was decided, and a worse one — the holder
     * can be given a clinical authority, or pointed at patient.abofonsa.com where an angel belongs.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-angel", authorities = { "ROLE_ANGEL" })
    void aTokenBearingTheCareAngelAuthorityStillReachesTheOwnScopedInboxReads() throws Exception {
        restMockMvc.perform(get("/api/messaging/conversations")).andExpect(status().isOk());
        restMockMvc.perform(get("/api/messaging/unread-count")).andExpect(status().isOk());
    }

    /**
     * And it still reads clinical data <em>from this service</em>, which is worth pinning because it is
     * the limit of what items 30 and 44 changed here. {@code GET /api/**} is open to any authenticated
     * caller — the mutation matrix is a rule about writes — so the refusal such a caller meets on a
     * cross-patient read is the <b>gateway's</b>, at {@code /services/**}, before the request ever
     * arrives. Narrowing this service's read rule to match would also lock out every applicant, which
     * is not what was decided.
     */
    @Test
    @WithMockGatewayUser(authorities = { "ROLE_ANGEL" })
    void aTokenBearingTheCareAngelAuthorityStillReadsWhatAnyAuthenticatedCallerReads() throws Exception {
        restMockMvc.perform(get("/api/categories")).andExpect(status().isOk());
    }
}
