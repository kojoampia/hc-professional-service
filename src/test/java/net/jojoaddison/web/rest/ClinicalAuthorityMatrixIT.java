package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * not reached that way and never was: {@code GET /api/profile} takes no subject at all and
 * stays open to any authenticated caller. ⚠ <b>Since F3 both paths are served by the same class</b>,
 * {@code ProfileResource}, gated per handler — so the cases below are no longer merely a matrix view
 * of two resources but <b>the only thing separating two surfaces inside one file</b>.
 * {@code ProfileResourceIT} and {@code OwnProfilePathIT} hold the endpoints' own cases;
 * what this class holds is the <i>matrix</i> view — that the refusal applies to a clinician and to a
 * role-less applicant alike.
 *
 * <p><b>⚠ And one surface has no {@code GET} mapping left, which is not an exception to the read rule
 * but is held here.</b> The three unowned {@code GET}s on {@code /api/personal-documents} are
 * <b>deleted</b> — S1, backlog.md row 226 — so the cases for them assert 405 and 404 rather than 403:
 * a refusal would mean a handler that still exists. <b>Deleted and gated are different claims</b>, and
 * this class is where the difference is visible, with a positive control beside them so an application
 * that answers nothing cannot read as a successful deletion. ⛔ <b>"No {@code GET} mapping" is not "no
 * read"</b>: that resource's {@code PATCH} still answers with a whole document and is backlog.md row
 * 227, named in the section comment and deliberately asserted nowhere.
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
    // gate honest rather than merely strict: GET /api/profile resolves the caller from
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
     * endpoint that cannot name anyone else — {@code GET /api/profile}, which is where both
     * {@code web/} and {@code mobile/} read it from since F8 re-pointed them.
     *
     * <p>⚠ <b>This case used to call {@code /api/onboarding/profile}</b>, and the path it names is
     * the whole of its value now that F8 has retired that one: the <em>previous</em> version of this
     * test would have gone on passing after the retirement, because an unmapped path under
     * {@code /api/onboarding/**} answers 404 too — the status it asserts. A 404 for "no row yet" and
     * a 404 for "no such endpoint" are indistinguishable here, which is exactly the shape of green
     * this estate keeps finding, so the re-point was not optional bookkeeping.
     *
     * <p>A clinician who has not completed onboarding has no profile row yet and gets a 404; that is
     * {@code ProfileResource.getOwnProfile}'s documented answer and not an authorization one. What
     * this asserts is the only thing item 143 could have broken and did not: <b>not a 403</b>.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-doctor", authorities = { "ROLE_DOCTOR" })
    void aClinicianStillReadsTheirOwnProfileThroughTheEndpointThatCannotNameAnyoneElse() throws Exception {
        restMockMvc.perform(get("/api/profile")).andExpect(status().isNotFound());
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
    // assert what it admits — above all that it does not reach /api/personal-documents, PLURAL, which
    // since S1 closed (backlog.md row 226) is the write-only admin data-maintenance surface for any
    // clinician's documents. Its three unowned GETs are DELETED, not gated, and the cases at the end of
    // this section are what holds them deleted.

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
     * <p>⚠ <b>Asserted on the WRITES, and until S1 closed that was the only place it could be
     * asserted.</b> The plural {@code GET}s used to answer a role-less caller — no
     * {@code @PreAuthorize}, falling to {@code /api/** -> .authenticated()} — so a {@code GET} here
     * could not tell a widened matcher from the existing hole, and this javadoc said so and named S1 as
     * open. <b>It is closed</b> (backlog.md row 226): the three reads are deleted, and
     * {@link #thePluralDocumentReadsDoNotExistForARoleLessApplicant} and its two siblings below are the
     * read cases this paragraph said could not be written. The writes remain the discriminator for
     * <em>this</em> case, because they are the only verbs the plural path still answers at all: if the
     * singular rule reached it, these three would be admitted to the handler instead of refused.
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

    // --- The plural GET MAPPINGS are gone, and these are the cases that could not be written before -
    //
    // S1, closed by backlog.md row 226. `PersonalDocumentResource` carried GET "", GET /{id} and
    // GET /profile/{profileId}, none of them annotated, all three returning `data` INLINE — and
    // `PersonalDocument.data` is a bare byte[] with no @JsonProperty restriction, so the document
    // itself was on the wire. Measured on the quality stack as the seeded `carer`, an account with no
    // Profile at all: GET /api/personal-documents answered 200 with 2,401,251 bytes — five documents
    // under one profile that was not the caller's, CERTIFICATE/LICENSE/PASSPORT/GHANACARD/PASSPHOTO —
    // and GET /api/personal-documents/{id} answered 200 with 281,395 bytes. The three gateways share
    // one signing key and validate-origin is false, so the caller could as easily have been an
    // hc-admin or hc-patient account.
    //
    // WHAT THESE CASES ASSERT IS THAT NO `GET` MAPPING ANSWERS ANYBODY, which is why there is a case
    // per authority band and no 403 anywhere in them: a 403 would mean a rule refusing a handler that
    // still exists. ⚠ IT IS NOT THE CLAIM THAT THIS RESOURCE CANNOT BE READ. PATCH /{id} returns the
    // merged row through `wrapOrNotFound`, so a merge-patch body carrying only `id` answers 200 with
    // the whole document, `data` included — measured as ROLE_NURSE against a foreign profileId — which
    // is a read reachable by the six CLINICAL_MUTATION authorities for an id they already hold. That
    // residue is backlog.md row 227's and is deliberately NOT asserted here: a test pinning it in place
    // would be worse than the row naming it.
    //
    // These statuses are DISPATCH answers reached THROUGH `/api/** -> .authenticated()`, and the two
    // values differ for a reason worth keeping:
    //
    //   405 on "" and /{id}      — the path pattern still matches, because POST is mapped on one and
    //                              PUT/PATCH/DELETE on the other, so Spring rejects the METHOD, and the
    //                              `Allow` header carries the inventory of what is left. Asserted below
    //                              as a SET: three observations of /{id} gave three different orders
    //                              ("PUT, PATCH, DELETE", "PATCH, DELETE, PUT", "DELETE, PUT, PATCH"),
    //                              so the order is not a property of the response and must not be
    //                              quoted. An earlier version of this comment quoted one of them.
    //   404 on /profile/{id}     — nothing is mapped at two segments below the base at all, so there
    //                              is no route. (/{id} does not match it; it is one segment.)
    //
    // ⚠ MEASURED, NOT REASONED. The first version of these cases expected 404 throughout and failed
    // with `Status expected:<404> but was:<405>` on the first two — which is the only reason the
    // distinction above is written down rather than guessed. If a verb is ever added or removed on the
    // plural resource these values move, and that is correct: they describe what the surface answers,
    // not a constant somebody chose.
    //
    // ⛔ AND THE TWO HALVES ARE NOT EQUALLY STRONG GUARDS. A 405 is typo-proof: misspell the path and
    // the answer is 404, so the assertion fails. THE 404 IS NOT — /api/personal-doccuments/profile/zz,
    // /api/personal-documents/nonsense/zz and /profile/zz/extra all answer 404 too, so that one line
    // would pass against a path this service never had. It is still a working guard (restoring
    // GET /profile/{profileId} turns it red) but it holds the route's absence, not the route's
    // identity. Do not copy it as a pattern for asserting that some other endpoint is gone.

    /**
     * A role-less applicant — which is also what a sibling stack's patient and a token still bearing
     * {@code ROLE_ANGEL} amount to here.
     *
     * <p>{@code HEAD} beside {@code GET} on both plural paths, because Spring MVC dispatches a
     * {@code HEAD} to a {@code @GetMapping} handler and a body-less read of a document list is still a
     * read — the near-miss item 143 records. There is no {@code @GetMapping} left for it to reach, and
     * these are what fail if one comes back. ⚠ The 405 is a <em>dispatcher</em> answer and therefore
     * authority-independent, which is why the two sibling cases below do not repeat the {@code HEAD}
     * probes: this case covers them for every band at once.
     *
     * <p><b>The {@code Allow} header is asserted as a set, and that is the point of asserting it.</b>
     * It is the inventory of verbs this path has left, so it fails if one is added — and the set, not
     * the string: three observations of {@code /{id}} produced three different orders, so an assertion
     * on the value would be a flake and a quoted value in a comment is how this drifted.
     *
     * <p>The last line is the weak one and is marked as such in the section comment: a misspelled path
     * answers 404 as readily as a deleted one, so the assertion beside it — <b>a path this service has
     * never had answers the same 404</b> — is there to stop a reader mistaking that line for proof that
     * {@code /profile/{profileId}} in particular is gone.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void thePluralDocumentReadsDoNotExistForARoleLessApplicant() throws Exception {
        MvcResult collection = restMockMvc.perform(get("/api/personal-documents")).andExpect(status().isMethodNotAllowed()).andReturn();
        assertThat(allowedMethods(collection)).as("what the collection path has left").containsExactlyInAnyOrder("POST");
        restMockMvc.perform(head("/api/personal-documents")).andExpect(status().isMethodNotAllowed());

        MvcResult byId = restMockMvc
            .perform(get("/api/personal-documents/{id}", "matrix-any-document"))
            .andExpect(status().isMethodNotAllowed())
            .andReturn();
        assertThat(allowedMethods(byId)).as("what the /{id} path has left").containsExactlyInAnyOrder("PUT", "PATCH", "DELETE");
        restMockMvc.perform(head("/api/personal-documents/{id}", "matrix-any-document")).andExpect(status().isMethodNotAllowed());

        restMockMvc.perform(get("/api/personal-documents/profile/{profileId}", "matrix-any-profile")).andExpect(status().isNotFound());
        restMockMvc.perform(get("/api/personal-doccuments/profile/{profileId}", "matrix-any-profile")).andExpect(status().isNotFound());
    }

    /**
     * The {@code Allow} header as a set of verbs.
     *
     * <p>Spring builds it from the mapping's allowed methods and <b>guarantees no order</b>: measured
     * {@code "PUT, PATCH, DELETE"}, {@code "PATCH, DELETE, PUT"} and {@code "DELETE, PUT, PATCH"} on
     * three runs of the same request. So the only assertable thing is membership, and a test that
     * compared the string would pass or fail by luck.
     */
    private static Set<String> allowedMethods(MvcResult result) {
        String allow = result.getResponse().getHeader(HttpHeaders.ALLOW);
        assertThat(allow).as("a 405 must say what is allowed").isNotBlank();
        return Arrays.stream(allow.split(",")).map(String::trim).collect(Collectors.toSet());
    }

    /**
     * The role that was measured reading a colleague's passport. A carer is read-only in v1, so before
     * row 226 every one of these answered 200 and the write cases above were the only thing this class
     * could say about the plural path.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-carer", authorities = { "ROLE_CARER" })
    void thePluralDocumentReadsDoNotExistForAReadOnlyDiscipline() throws Exception {
        restMockMvc.perform(get("/api/personal-documents")).andExpect(status().isMethodNotAllowed());
        restMockMvc.perform(get("/api/personal-documents/{id}", "matrix-any-document")).andExpect(status().isMethodNotAllowed());
        restMockMvc.perform(get("/api/personal-documents/profile/{profileId}", "matrix-any-profile")).andExpect(status().isNotFound());
    }

    /**
     * ⛔ <b>Nor for an administrator, and that is the deliberate part.</b>
     *
     * <p>An admin may write every one of the verbs this resource still answers, so "the mappings were
     * removed for lack of authority" would predict a 200 here. <b>The mappings are gone, not gated</b>
     * — there is no privileged read of the <em>whole collection</em> in this service. A reviewer is
     * already served twice over without one: the document list is
     * {@code GET /api/professional-application/{id}/documents}, behind {@code assertAdminOrOwner} with
     * {@code data} nulled on every row, and the scan itself is
     * {@code GET /api/personal-document/{id}/content}, behind {@code assertOwnerOrReviewer}, one
     * document the reviewer named.
     *
     * <p>This is the case that fails if somebody answers a future "but admin needs to list documents"
     * by putting a {@code @PreAuthorize}'d {@code GET} back on the plural path. That request is a third
     * answer to "who may read a document" and both existing answers are subject-scoped.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-admin", authorities = { "ROLE_ADMIN" })
    void thePluralDocumentReadsDoNotExistForAnAdministratorEither() throws Exception {
        restMockMvc.perform(get("/api/personal-documents")).andExpect(status().isMethodNotAllowed());
        restMockMvc.perform(get("/api/personal-documents/{id}", "matrix-any-document")).andExpect(status().isMethodNotAllowed());
        restMockMvc.perform(get("/api/personal-documents/profile/{profileId}", "matrix-any-profile")).andExpect(status().isNotFound());
    }

    /**
     * ⭐ <b>The positive control for the three cases above, and it is not optional.</b>
     *
     * <p>If every probe 404s after a deletion then so does a dead application — and a 405 is barely
     * better evidence, since an empty dispatcher would answer much the same for anything. So the same
     * run has to show that something which must still answer does answer, and the two things chosen
     * are the two the deletion could plausibly have taken with it:
     *
     * <ol>
     *   <li><b>the singular own-document list reaches its own handler</b> — the 400 is
     *       {@code ownProfile}'s "create your professional profile first", which only a handler that
     *       ran can produce. <b>This is the leg the dead-application discriminator rests on</b>, and it
     *       is load-bearing: moving that {@code @GetMapping} away turns it red
     *       {@code expected:<400> but was:<405>}; and
     *   <li><b>the filter chain is installed and its mutation rule covers this path pattern</b> — the
     *       403s on {@code POST} and {@code DELETE}.
     * </ol>
     *
     * <p>⚠ <b>What leg 2 does NOT establish, corrected after review — and it is the item-143 shape
     * again.</b> This javadoc used to say the 403 "cannot be produced by an unmapped path". It can:
     * {@code POST /api/** -> CLINICAL_MUTATION} refuses a {@code ROLE_USER} caller in the chain,
     * <em>before</em> dispatch, handler or no handler. Moving {@code @PostMapping("")} and
     * {@code @DeleteMapping("/{id}")} to another path leaves this case <b>green</b>. So leg 2 is
     * evidence about the rule, not about the mapping. <b>The write surface's existence is held by the
     * three 405 assertions instead</b> — unmap every verb on the collection and they go
     * {@code 405 -> 404}. That distinction is worth spelling out precisely because the layer this
     * comment advertised as operative was not the operative one: deleting the 405 cases on the strength
     * of the old sentence would have lost the guard with every test still green.
     *
     * <p><b>Its failure is distinguishable from the deletion cases' success by being its own test.</b>
     * Three deletion cases green and this one red says "the reads are gone and so is everything else";
     * three red and this one green says the reads came back. Folding the control into those cases would
     * have made both readings one failure.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void theSurfaceAroundTheDeletedReadsStillAnswers() throws Exception {
        restMockMvc.perform(get("/api/personal-document")).andExpect(status().isBadRequest());
        restMockMvc
            .perform(post("/api/personal-documents").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        restMockMvc.perform(delete("/api/personal-documents/{id}", "matrix-any-document")).andExpect(status().isForbidden());
    }

    // --- /api/professional-application: one base path, three gates, T3's T0 rules ----------------
    //
    // profile.md step 4 is declared by an APPLICANT holding ROLE_USER and nothing else, and the
    // fifteen /api/onboarding/applications mappings migrated onto this base — so the rules here
    // REPLACE what `/api/onboarding/**` used to give them rather than adding anything. Miss them and
    // every applicant is locked out of their own step 4, with the refusal attributed to the service.
    //
    // THIS PATH IS THE WIDEST T0 CASE IN THE SERVICE, because one base carries three gates: the
    // applicant's own /me island, three admin-or-owner /{id} reads, and an admin-only surface made
    // of the review queue, seven transitions and /compliance. The cases below assert each boundary
    // from BOTH sides, because the rules that separate them are ordered wildcards and ordering is
    // not something reading the configuration settles — in particular
    // `PUT /api/professional-application/*/**` matches `/me/submit`, so if the /me rule were ever
    // moved below it an applicant would be 403'd submitting their own application by a rule written
    // for the reviewer.

    /**
     * An applicant reads their own application. <b>Not a 403</b> is the assertion; the 404 is
     * {@code getOwnApplication}'s answer for someone who has never applied, which is the normal
     * state and not an authorization answer.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantReachesTheirOwnApplication() throws Exception {
        restMockMvc.perform(get("/api/professional-application/me")).andExpect(status().isNotFound());
    }

    /** And a {@code HEAD} of it — the verb a {@code GET}-scoped rule drops onto the matrix below. */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantsHeadOfTheirOwnApplicationIsNotRefused() throws Exception {
        restMockMvc.perform(head("/api/professional-application/me")).andExpect(status().isNotFound());
    }

    /**
     * An applicant <b>starts</b> an application, which is the half the mutation matrix would refuse.
     *
     * <p>{@code ROLE_USER} is outside {@code CLINICAL_MUTATION}, so without the island rule above
     * {@code POST /api/** -> CLINICAL_MUTATION} every applicant is 403'd beginning their own
     * application while the {@code /me} read keeps working on
     * {@code /api/** -> .authenticated()} — the asymmetry that reads as a broken form rather than as
     * a missing rule.
     *
     * <p>⚠ Sent with {@code agreed: false} deliberately, so the assertion is the handler's own 400
     * — {@link net.jojoaddison.service.OnboardingService#CONSENT_REQUIRED} — reached
     * <em>because</em> authorization let the request through, and no application is created by a
     * matrix case.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCanPostTheirOwnApplication() throws Exception {
        restMockMvc
            .perform(
                post("/api/professional-application")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":false}")
            )
            .andExpect(status().isBadRequest());
    }

    /**
     * ⛔ <b>Step 4's two writes reach the applicant, and this is the case the ordered wildcards put
     * most at risk.</b>
     *
     * <p>Both are {@code PUT} under {@code /me}, and the reviewer rule
     * {@code PUT /api/professional-application/*&#47;**} matches them as surely as it matches
     * {@code /{id}/activate}. The 404 is {@code getOwnApplication}'s, so each assertion says two
     * things at once: the mutation matrix did not refuse it, and neither did the {@code ROLE_ADMIN}
     * rule written for the transitions.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCanWriteTheirOwnConsentAndSubmit() throws Exception {
        restMockMvc
            .perform(
                put("/api/professional-application/me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true}")
            )
            .andExpect(status().isNotFound());
        restMockMvc
            .perform(
                put("/api/professional-application/me/submit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true}")
            )
            .andExpect(status().isNotFound());
        restMockMvc.perform(put("/api/professional-application/me/complete-profile")).andExpect(status().isNotFound());
    }

    /**
     * ⛔ <b>And the island stops at {@code /me}: an applicant is refused the review queue.</b>
     *
     * <p>{@code GET} on the bare path returns <em>every</em> application on the estate — login,
     * requested authority, status and the careers attribution for each — so it is {@code ROLE_ADMIN}
     * where the same literal admits an applicant's {@code POST}. That makes this the one path in the
     * service where two verbs on one exact literal answer under two different authorities, which is
     * why {@code HEAD} is spelled beside {@code GET} in the chain rather than left to fall through:
     * a body-less read of a queue still answers how many people are waiting (item 143).
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCannotReadTheReviewQueue() throws Exception {
        restMockMvc.perform(get("/api/professional-application")).andExpect(status().isForbidden());
        restMockMvc.perform(head("/api/professional-application")).andExpect(status().isForbidden());
    }

    /** Nor may an applicant drive a reviewer transition on anybody's application, their own included. */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCannotDriveAReviewerTransition() throws Exception {
        restMockMvc.perform(put("/api/professional-application/{id}/activate", "matrix-any-application")).andExpect(status().isForbidden());
        restMockMvc
            .perform(
                put("/api/professional-application/{id}/decide", "matrix-any-application")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"APPROVED\"}")
            )
            .andExpect(status().isForbidden());
    }

    /**
     * Nor the compliance surface, on any verb.
     *
     * <p>Its rule is method-agnostic because every one of its four mappings is {@code ROLE_ADMIN} —
     * there is no verb under this prefix anyone else may use — which is the shape in which a
     * {@code HEAD} cannot fall through to a different authority. {@code ComplianceResource} carried
     * a class-level annotation and <em>no</em> chain rule of its own; this is the layer T3 added.
     */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCannotReachTheComplianceSurface() throws Exception {
        restMockMvc.perform(get("/api/professional-application/compliance/metrics")).andExpect(status().isForbidden());
        restMockMvc.perform(head("/api/professional-application/compliance/expiring")).andExpect(status().isForbidden());
        restMockMvc.perform(post("/api/professional-application/compliance/sweep")).andExpect(status().isForbidden());
    }

    /**
     * And the admin surface is admin's, not every clinician's: a carer is the read-only discipline
     * and a doctor the widest clinical one, and both are refused the queue, the transitions and
     * compliance.
     *
     * <p>Worth both roles rather than one: the queue rule is an authority list, so a role added to
     * {@code CLINICAL_MUTATION} or {@code CLINICAL_AND_ADMIN} later must not acquire the review
     * queue by being named somewhere else.
     */
    @Test
    @WithMockGatewayUser(authorities = { "ROLE_CARER" })
    void aReadOnlyDisciplineCannotReachTheApplicationAdminSurface() throws Exception {
        restMockMvc.perform(get("/api/professional-application")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/professional-application/compliance/metrics")).andExpect(status().isForbidden());
        restMockMvc.perform(put("/api/professional-application/{id}/activate", "matrix-any-application")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockGatewayUser(authorities = { "ROLE_DOCTOR" })
    void theWidestClinicalRoleCannotReachTheApplicationAdminSurface() throws Exception {
        restMockMvc.perform(get("/api/professional-application")).andExpect(status().isForbidden());
        restMockMvc.perform(head("/api/professional-application")).andExpect(status().isForbidden());
        restMockMvc.perform(get("/api/professional-application/compliance/events")).andExpect(status().isForbidden());
    }

    /**
     * ⛔ <b>The document verdict moved onto the applicant island's base and must not have become
     * reachable from it.</b>
     *
     * <p>{@code PUT /api/personal-document/{id}/verify} and {@code .../reject} were
     * {@code OnboardingDocumentResource}'s on {@code /api/onboarding/documents} and are
     * {@link PersonalDocumentReviewResource}'s on {@code /api/personal-document} since T3 — a base
     * whose chain rule is {@code .authenticated()} for the applicant's own upload. So the verdict
     * needed a rule of its own, and these cases are what distinguishes "refused by the new
     * {@code ROLE_ADMIN} rule" from "admitted by the island and refused only by the annotation".
     *
     * <p>A carer is the case that matters: a read-only clinical discipline reaching a credential
     * verdict is the failure {@code docs/CLAUDE.md} warns about at the point where it was nearly
     * shipped.
     */
    @Test
    @WithMockGatewayUser(authorities = { "ROLE_CARER" })
    void aReadOnlyDisciplineCannotVerifyOrRejectADocument() throws Exception {
        restMockMvc.perform(put("/api/personal-document/{id}/verify", "matrix-any-document")).andExpect(status().isForbidden());
        restMockMvc
            .perform(
                put("/api/personal-document/{id}/reject", "matrix-any-document")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"matrix\"}")
            )
            .andExpect(status().isForbidden());
    }

    /** And neither can the applicant whose own documents live under the same base. */
    @Test
    @WithMockGatewayUser(login = "matrix-applicant", authorities = { "ROLE_USER" })
    void anApplicantCannotVerifyTheirOwnDocument() throws Exception {
        restMockMvc.perform(put("/api/personal-document/{id}/verify", "matrix-any-document")).andExpect(status().isForbidden());
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
