package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.OnboardingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code api/professional-application} — step 4's model and its two writes (profile.md step 4, T3).
 *
 * <p>Sibling of {@code OwnProfilePathIT} and {@code OwnPersonalDocumentResourceIT}: one class per
 * specified endpoint, asserting the <b>model the specification gives</b> and the behaviours the
 * migrated path had to keep. {@code OnboardingFlowIT} and {@code ReviewerFlowIT} already walk the
 * whole lifecycle through these paths; what is here is only what T3 decided.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ProfessionalApplicationResourceIT {

    private static final String BASE = "/api/professional-application";

    private static final String APPLICANT = "t3-applicant";

    /** Step 4's body, as the specification types it: the tick and the role, and nothing else. */
    private static final String CONSENT = "{\"authority\":\"ROLE_NURSE\",\"agreed\":true}";

    /**
     * The tick alone — a body naming <b>no</b> authority, which since the owner's decision of
     * 2026-10-09 leaves a stored role as it is rather than blanking it.
     */
    private static final String CONSENT_WITHOUT_AUTHORITY = "{\"agreed\":true}";

    /** And the blank form of the same thing, which must behave identically. */
    private static final String CONSENT_WITH_BLANK_AUTHORITY = "{\"agreed\":true,\"authority\":\"\"}";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private OnboardingEventRepository eventRepository;

    @BeforeEach
    @AfterEach
    void cleanup() {
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
        personalDocumentRepository.deleteAll();
        eventRepository.deleteAll();
    }

    // --- The step-4 model ------------------------------------------------------------------------

    /**
     * ⭐ <b>The four fields {@code profile.md} step 4 names, on the wire and in storage.</b>
     *
     * <p>{@code agreed} (the tick), {@code authority} (the role requested), {@code agreedDate} (a
     * <b>server stamp</b>) and {@code profileId} (<i>"Set to {@code Profile.id}"</i>). Asserted on
     * the response <em>and</em> re-read from the repository, because the wire names and the stored
     * names are two different things here — {@code agreed_date} and {@code authority} are what the
     * {@code @Field} annotations write, and a rename that moved only one of the two would still
     * serialise correctly while the migration pointed at nothing.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void postStoresStepFoursFourFields() throws Exception {
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));

        Instant before = Instant.now();
        restMockMvc
            .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.agreed").value(true))
            .andExpect(jsonPath("$.authority").value("ROLE_NURSE"))
            .andExpect(jsonPath("$.agreedDate").isNotEmpty())
            .andExpect(jsonPath("$.profileId").value(profile.getId()))
            .andExpect(jsonPath("$.status").value("APPLICATION_STARTED"));

        ProfessionalApplication stored = applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow();
        assertThat(stored.isAgreed()).isTrue();
        assertThat(stored.getAuthority()).isEqualTo("ROLE_NURSE");
        assertThat(stored.getProfileId()).isEqualTo(profile.getId());
        assertThat(stored.getAgreedDate()).isNotNull().isAfterOrEqualTo(before);
    }

    /**
     * ⛔ <b>{@code agreedDate} is the server's and a body cannot set it.</b>
     *
     * <p>{@code profile.md} types it <i>"Server stamp"</i>, and the shape that honours that is an
     * allow-list record with no such component — so a client sending one is not refused, it is
     * <em>unable to be heard</em>. Asserted with a date far in the past, which is the direction that
     * matters: an applicant able to back-date their own consent could claim to have agreed to terms
     * that were not published yet.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aClientCannotSetTheConsentDate() throws Exception {
        restMockMvc
            .perform(
                post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true,\"agreedDate\":\"2001-01-01T00:00:00Z\"}")
            )
            .andExpect(status().isCreated());

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getAgreedDate()).isAfter(
            Instant.parse("2020-01-01T00:00:00Z")
        );
    }

    /**
     * ⛔ <b>And neither can a body set {@code status}</b>, which is the component whose absence from
     * the record carries the most weight: an applicant who could write it would grant themselves
     * {@code ACTIVE} and skip credential review outright. Same argument
     * {@code ProfileFieldOwnership} makes about the profile's own copy of this field.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aClientCannotSetTheStatus() throws Exception {
        restMockMvc
            .perform(
                post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true,\"status\":\"ACTIVE\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("APPLICATION_STARTED"));
    }

    // --- The behaviours the migrated path had to keep --------------------------------------------

    /**
     * Consent is mandatory to create an application — 400, with the service's own message.
     *
     * <p>Carried across from {@code POST /api/onboarding/applications} because {@code profile.md}'s
     * silence about it is not permission to drop it. Asserted against
     * {@link OnboardingService#CONSENT_REQUIRED} rather than a copy of its text, so a reword cannot
     * break this quietly.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void consentIsMandatoryToCreateAnApplication() throws Exception {
        restMockMvc
            .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"authority\":\"ROLE_NURSE\",\"agreed\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail", containsString(OnboardingService.CONSENT_REQUIRED)));
        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT))).isEmpty();
    }

    /** One application per account — a second create is 409, not a second row. */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSecondCreateIsRefused() throws Exception {
        restMockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isCreated());
        restMockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isConflict());
        assertThat(applicationRepository.findAll()).hasSize(1);
    }

    /**
     * The careers attribution survives the migration.
     *
     * <p>{@code source} is read by the review queue and by the WP7 funnel count
     * (careers-handoff-contract.md § 3), and it is normalised rather than stored raw — capped so the
     * field cannot be abused as free storage.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void theCareersAttributionIsCarriedThrough() throws Exception {
        restMockMvc
            .perform(
                post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true,\"source\":\"web-careers\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.source").value("web-careers"));
    }

    // --- Step 4's write: Save and Submit ---------------------------------------------------------

    /**
     * ⭐ <b>A Save on a COMPLETE application stores the answers, advances to
     * {@code CREDENTIAL_REVIEW} and appends an {@code OnboardingEvent} doing it</b> — the owner's
     * <i>"Save advances only when complete"</i> (2026-10-09).
     *
     * <p>The event is the half that cannot be read off the response, and it is the point of the
     * owner's instruction that the status write go through {@code OnboardingService.transition()}
     * rather than an assignment: a write that checks nothing and appends nothing leaves an
     * application whose own history does not contain the step. So this asserts the status <em>and</em>
     * that the trail gained a {@code PROFILE_COMPLETED → CREDENTIAL_REVIEW} row.
     *
     * <p>⛔ <b>It is also the half of T3's refused "Kafka-less Save" that survives the owner's
     * decision.</b> hc-admin consumes {@code onboarding.state COMPLETED} to learn that an application
     * is waiting on a reviewer, so a Save that reached {@code CREDENTIAL_REVIEW} <em>quietly</em>
     * would leave it in this service's queue and in nobody else's — <b>a consumer reading where
     * nobody writes is silence that looks like health</b>. The event is not assertable from here, so
     * the transition and its trail row stand in for it.
     *
     * <p>The authority sent here differs from the one the application was created with, which is
     * what makes "stores the requested authority" an assertion rather than a tautology. The
     * incomplete counterpart is {@link #aSaveStoresAndStaysPutWhenTheApplicationIsIncomplete}.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void saveStoresConsentAndAuthorityAndMovesToCredentialReview() throws Exception {
        readyToSubmit();

        restMockMvc
            .perform(
                put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content("{\"authority\":\"ROLE_PARAMEDIC\",\"agreed\":true}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CREDENTIAL_REVIEW"))
            .andExpect(jsonPath("$.authority").value("ROLE_PARAMEDIC"))
            .andExpect(jsonPath("$.agreed").value(true));

        ProfessionalApplication stored = applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ProfileStatus.CREDENTIAL_REVIEW);
        assertThat(stored.getSubmittedAt()).isNotNull();
        assertThat(eventRepository.findByApplicationIdOrderByAtAsc(stored.getId())).anyMatch(
            event -> event.getFromStatus() == ProfileStatus.PROFILE_COMPLETED && event.getToStatus() == ProfileStatus.CREDENTIAL_REVIEW
        );
    }

    /**
     * ⭐ <b>On a COMPLETE application Submit does what Save does</b> — which is the whole of what
     * the two paths still share, and the reason they share one service body.
     *
     * <p>⚠ <b>This case was named for a claim that is no longer true in general.</b> T3 read
     * {@code profile.md} step 4 — <i>"A <b>Save</b> and a <b>Submit</b> button store the consent and
     * the requested authority, and set {@code application.status} to {@code CREDENTIAL_REVIEW}"</i>
     * — as one operation behind two mappings. The owner's decision of 2026-10-09 keeps the identical
     * <em>effects</em> and separates the <em>preconditions</em>: when incomplete, Save stores and
     * stays put while Submit refuses. So the equivalence holds here, where the application is
     * complete, and nowhere else; {@link #neitherWriteReachesReviewWithoutTheMandatoryDocuments} is
     * the case that draws the difference.
     *
     * <p>Asserted as a <em>pair</em> with the Save case above, because "the two cannot drift apart on
     * the complete path" is the claim and it is only worth anything if both paths are exercised.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void onACompleteApplicationSubmitDoesWhatSaveDoes() throws Exception {
        readyToSubmit();

        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CREDENTIAL_REVIEW"))
            .andExpect(jsonPath("$.authority").value("ROLE_NURSE"));
    }

    /**
     * ⛔ <b>Neither write reaches the review queue without the mandatory documents</b> — and they
     * decline differently, which is the owner's decision of 2026-10-09.
     *
     * <p>{@code profile.md} conditions the move on <i>"when all requirements are satisfied"</i>
     * without naming a button, so <b>neither path advances</b>; what differs is the answer. Submit
     * <b>refuses with 400</b>, Save <b>stores and stays put with 200</b>. This is the case that would
     * have gone quiet had Save been built as a transitioning-but-ungated variant: an applicant with
     * no licence on file would have reached the review queue.
     *
     * <p>⚠ The status is re-read <em>after both</em> calls, because the claim that matters is not
     * which code each returned but that the application did not move — which is the only thing a
     * reviewer would ever see.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void neitherWriteReachesReviewWithoutTheMandatoryDocuments() throws Exception {
        readyToSubmit();
        personalDocumentRepository.deleteAll();

        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("certificate")))
            .andExpect(jsonPath("$.detail").value(containsString("license")));
        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROFILE_COMPLETED"));

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getStatus()).isEqualTo(
            ProfileStatus.PROFILE_COMPLETED
        );
    }

    // --- F-B: "all requirements", not step 3's documents -----------------------------------------

    /**
     * ⛔ <b>A single blank step-2 field refuses the submit, and the refusal names the requirement.</b>
     *
     * <p>{@code profile.md} § Gap Update: <i>"Submitting sets {@code Application.status} to
     * {@code CREDENTIAL_REVIEW} and triggers the Kafka event, <b>when all requirements are
     * satisfied</b>."</i> Before F-B the only check here was step 3's four documents, so this exact
     * applicant — every document uploaded, one field blank — got <b>200</b>, reached
     * {@code CREDENTIAL_REVIEW} and had {@code onboarding.state COMPLETED} published to hc-admin;
     * activation then failed with {@code ACTIVATION_REQUIRES_COMPLETE_PROFILE} <em>after a reviewer
     * had done the work</em>.
     *
     * <p>⭐ <b>{@code phoneNumber} is the field chosen on purpose.</b> It is one of the seven the
     * progress meter's own predicates never looked at, so this case is red under
     * {@code ProfileCompleteness} and would be green under the meter — which is the whole of why the
     * gate reads the stricter definition. {@code digitalAddress}, {@code town} and {@code district}
     * are the same shape and {@link #aSecondEmergencyContactIsRequiredToSubmit} covers the other
     * group.
     *
     * <p>The status is re-read, because "it answered 400" and "it did not advance the application"
     * are two claims and only the second is the defect.
     *
     * <p>⚠ <b>Submit only. Save answers 200 for this same applicant</b> — see
     * {@link #aSaveStoresAndStaysPutWhenTheApplicationIsIncomplete}, which is the owner's decision of
     * 2026-10-09 and the one behaviour the two paths do not share.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void oneBlankProfileFieldRefusesTheSubmitAndNamesIt() throws Exception {
        Profile profile = readyToSubmit();
        profileRepository.save(profile.phoneNumber(null));

        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString(OnboardingService.SUBMISSION_REQUIRES_ALL_REQUIREMENTS)))
            .andExpect(jsonPath("$.detail").value(containsString("profile")));

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getStatus()).isEqualTo(
            ProfileStatus.PROFILE_COMPLETED
        );
    }

    // --- The owner's Save/Submit split, 2026-10-09 ------------------------------------------------

    /**
     * ⭐ <b>Save stores the answers and stays put when the application is incomplete</b> — the owner's
     * <i>"Save should be non-advancing — store answers only"</i>.
     *
     * <p>200, not 400: an applicant saving mid-wizard is not making a mistake. The assertions are
     * four, because "it answered 200" is the least of what has to be true — the <b>answers must be
     * stored</b> (that is the whole purpose of the path), the status must <b>not</b> have moved, and
     * {@code submittedAt} must still be null, which is the field that would betray a transition
     * having been attempted.
     *
     * <p>The authority sent differs from the one the application was created with, so "it stored the
     * answers" is an assertion rather than a tautology.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSaveStoresAndStaysPutWhenTheApplicationIsIncomplete() throws Exception {
        Profile profile = readyToSubmit();
        profileRepository.save(profile.phoneNumber(null));

        restMockMvc
            .perform(
                put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content("{\"authority\":\"ROLE_PARAMEDIC\",\"agreed\":true}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROFILE_COMPLETED"))
            .andExpect(jsonPath("$.authority").value("ROLE_PARAMEDIC"));

        ProfessionalApplication stored = applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow();
        assertThat(stored.getAuthority()).as("the answers are stored — that is what Save is for").isEqualTo("ROLE_PARAMEDIC");
        assertThat(stored.getStatus()).as("and nothing advanced").isEqualTo(ProfileStatus.PROFILE_COMPLETED);
        assertThat(stored.getSubmittedAt()).as("nor was a transition even attempted").isNull();
    }

    /**
     * ⚠ <b>Save is repeatable — twice while incomplete, and twice while complete.</b>
     *
     * <p>The owner's <i>"It must be repeatable. Saving twice must not 409"</i>. Both halves matter and
     * they fail differently: an incomplete Save repeated would 409 if the path ever started
     * transitioning unconditionally, and a <em>complete</em> Save repeated would 409 if it let the
     * state machine answer instead of checking {@code LEGAL_TRANSITIONS} first — because the second
     * call starts from {@code CREDENTIAL_REVIEW}, out of which that move is illegal.
     *
     * <p>⭐ The second half is the one the one-method shape could not have had, and it is why
     * {@code aSecondWriteIsRefusedByTheStateMachine} moved to Submit.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSaveCanBeCalledTwice() throws Exception {
        Profile profile = readyToSubmit();
        profileRepository.save(profile.phoneNumber(null));

        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());

        // Now complete it, so the next Save advances — and the one after that finds itself already
        // in CREDENTIAL_REVIEW, which is the half that needs the legality check rather than the
        // state machine's refusal.
        profileRepository.save(profile.phoneNumber("+233300000000"));
        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CREDENTIAL_REVIEW"));
        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CREDENTIAL_REVIEW"));
    }

    /**
     * ⛔ <b>One emergency contact is not two</b> — {@code profile.md} step 2: <i>"At least <b>two</b>
     * emergency contacts are required."</i>
     *
     * <p>The {@code nextOfKin} half of the same finding, asserted separately because it is a
     * different group key and a different predicate: {@code ProfileCompleteness.contactsProvided}
     * requires at least {@code REQUIRED_CONTACTS} and <em>every</em> contact complete, where the
     * meter counts the complete ones.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSecondEmergencyContactIsRequiredToSubmit() throws Exception {
        Profile profile = readyToSubmit();
        profileRepository.save(profile.contacts(java.util.List.of(profile.getContacts().get(0))));

        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("nextOfKin")));
    }

    /**
     * ⛔ <b>Step 4's own other half: an applicant who has <em>never declared</em> a role is refused at
     * Submit.</b>
     *
     * <p>{@code profile.md} step 4 is <i>"The professional declares the role they are applying for
     * <b>and</b> consents"</i>, and nothing refused a blank role before F-B — the application simply
     * stored {@code authority: null} over whatever it held and went to review with no discipline on
     * it. Consent is refused earlier and with its own message, because a withheld tick is a different
     * answer from an incomplete form.
     *
     * <p>⚠ <b>The premise changed with the owner's decision of 2026-10-09, not the claim.</b> This
     * walked {@link #readyToSubmit()}, which <em>declares</em> {@code ROLE_NURSE} at the create — so
     * the refusal it observed depended on the Submit body blanking that stored role on its way to the
     * gate. Now that a body naming none leaves the stored value alone, the application has to have
     * been created without one for "no authority" to be true of it at all; that is what
     * {@code CONSENT_WITHOUT_AUTHORITY} is doing here. The other direction — an applicant who
     * <em>did</em> declare one and submits without restating it — is
     * {@link #aSubmitNamingNoAuthorityUsesTheStoredRole}.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSubmissionNamingNoAuthorityIsRefused() throws Exception {
        readyToSubmit(CONSENT_WITHOUT_AUTHORITY);

        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT_WITHOUT_AUTHORITY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("authority")));

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getStatus())
            .as("an applicant with no discipline is not quietly advanced")
            .isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }

    /**
     * ⭐ <b>A Submit that does not restate the authority is satisfied by the one already stored.</b>
     *
     * <p>The consequence of <i>"don't blank it"</i> on the gate rather than on the field: step 4's
     * requirement is evaluated against the authority the application <b>holds after the write</b>, not
     * against the body. Keyed on the body instead, this Submit would be refused with
     * {@code authority} among the missing requirements <em>while the stored application plainly
     * carries {@code ROLE_NURSE}</em> — a refusal naming something the applicant has already done,
     * which is the class of defect this repository's own notes call a message that is not true.
     *
     * <p>⚠ Asserted through the full walk, so the 200 is a real advance: {@code CREDENTIAL_REVIEW},
     * {@code submittedAt} stamped, and the authority still on the row for the reviewer to act on.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSubmitNamingNoAuthorityUsesTheStoredRole() throws Exception {
        readyToSubmit();

        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT_WITHOUT_AUTHORITY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CREDENTIAL_REVIEW"))
            .andExpect(jsonPath("$.authority").value("ROLE_NURSE"));

        ProfessionalApplication stored = applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow();
        assertThat(stored.getAuthority()).as("the declared discipline reaches the reviewer").isEqualTo("ROLE_NURSE");
        assertThat(stored.getSubmittedAt()).isNotNull();
    }

    // --- The authority is validated against the eight professional disciplines --------------------

    /**
     * ⛔ <b>{@code {"agreed":true,"authority":"banana"}} is a 400 on every path that writes one</b> —
     * the create, Save and Submit.
     *
     * <p>All three stored it and answered 2xx before, after which the review queue rendered
     * {@code healthConnect.roles.banana} — the raw translation key, mid-screen, in all four locales —
     * and the review-detail page handed the value to the gateway's {@code grantAuthority}. ⚠ Each path
     * is asserted separately even though the gate is on one service method, because the three reach it
     * through different handlers and one of them is a different service method: a check wired into two
     * of the three would leave the third storing the value with every test here green.
     *
     * <p><b>The storage assertions are the point, not the status.</b> The create must leave no
     * application at all, and Save must leave the authority the application already held — a 400 that
     * stored first would put a non-member in the field the reviewer acts on and merely tell the caller
     * about it.
     *
     * <p>⚠ That second assertion is also the invalid-value half of the owner's <i>"don't blank it"</i>
     * decision of 2026-10-09 — a refused Save changes nothing — and is left here rather than copied
     * into {@link #aSaveStoresWhatItNamesAndDoesNotBlankWhatItDoesNot}, since the refusal and the
     * non-blanking are one line of this walk and two copies of an assertion is how one of them goes
     * stale.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void anAuthorityOutsideTheEightIsRefusedOnEveryWritePath() throws Exception {
        String banana = "{\"authority\":\"banana\",\"agreed\":true}";

        restMockMvc
            .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(banana))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("authority")));
        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT))).as("a refused create stores nothing").isEmpty();

        readyToSubmit();

        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(banana))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("authority")));
        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(banana))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("authority")));

        ProfessionalApplication stored = applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow();
        assertThat(stored.getAuthority()).as("neither refusal reached the field the reviewer acts on").isEqualTo("ROLE_NURSE");
        assertThat(stored.getStatus()).as("and nothing advanced").isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }

    /**
     * ⭐ <b>Every one of the eight is accepted, read from
     * {@link AuthoritiesConstants#PROFESSIONAL_DISCIPLINES} rather than listed here.</b>
     *
     * <p>So a ninth discipline is covered on the day it is added to this service's own copy of the
     * authorities, with nobody having edited this file — the reason
     * {@code JhipsterEnumFieldValuesTest} derives its expectations too. The literal eight are written
     * down in {@code AuthoritiesConstantsUnitTest}, which is where a <em>removal</em> has to be
     * noticed; a derived loop cannot see one, and a derived loop is what proves the gate admits
     * whatever that constant says.
     *
     * <p>Through Save rather than the create, because Save is the repeatable write and can therefore
     * carry all eight in one application's lifetime. It stays in {@code PROFILE_COMPLETED} throughout:
     * {@code readyToSubmit} leaves the profile short of nothing, so the last discipline would advance
     * it — hence the blanked {@code phoneNumber}, which keeps every iteration identical.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void everyProfessionalDisciplineIsAccepted() throws Exception {
        Profile profile = readyToSubmit();
        profileRepository.save(profile.phoneNumber(null));

        assertThat(AuthoritiesConstants.PROFESSIONAL_DISCIPLINES).as("the derived set is not empty, or this asserts nothing").isNotEmpty();

        for (String discipline : AuthoritiesConstants.PROFESSIONAL_DISCIPLINES) {
            restMockMvc
                .perform(
                    put(BASE + "/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authority\":\"" + discipline + "\",\"agreed\":true}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authority").value(discipline));
        }
    }

    /**
     * ⛔ <b>{@code ROLE_ADMIN} and {@code ROLE_USER} are refused by name.</b>
     *
     * <p>Neither is a requestable role and the two fail differently if the gate is written as "any
     * authority this service knows about": {@code ROLE_USER} is what every applicant already holds, so
     * accepting it would put a non-discipline in the reviewer's field through a value the token
     * already carries — and <b>{@code ROLE_ADMIN} is an applicant asking to be granted the reviewer's
     * own authority</b>, in the field {@code review-detail-page.component.ts} passes to the gateway's
     * {@code grantAuthority}. {@code web}'s {@code careers-handoff.service.ts} excludes exactly these
     * two from its {@code KNOWN_TRACKS} for the same reason.
     *
     * <p>Spelled as literals, deliberately: these are the values that must <em>not</em> be accepted,
     * and deriving them from the same constant the gate derives its allow-list from would assert
     * nothing.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void theAdministratorAndBaseUserAuthoritiesAreNotRequestable() throws Exception {
        for (String refused : java.util.List.of("ROLE_ADMIN", "ROLE_USER")) {
            restMockMvc
                .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"authority\":\"" + refused + "\",\"agreed\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("authority")));
            assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT))).isEmpty();
        }
    }

    /**
     * ⭐ <b>A Save stores the authority it <em>names</em> and leaves a stored one alone when it names
     * none</b> — the owner's <i>"Save should store what I named — don't blank it"</i> (2026-10-09).
     *
     * <p>⛔ <b>This case asserted the opposite until that decision, and the defect it was pinning was
     * live.</b> It was {@code aWriteNamingNoAuthorityBehavesAsItDidBefore}, and "as it did before"
     * meant the unconditional {@code application.authority(authority)}: a body of
     * {@code {"agreed":true}} <b>erased the role the applicant had already declared</b> and answered
     * 200 — the field {@code review-detail-page.component.ts} hands to the gateway's
     * {@code grantAuthority}, cleared by a client that merely round-tripped the consent tick. The old
     * case could not see it because it created the application <em>without</em> an authority, so there
     * was never a stored value for the Save to destroy.
     *
     * <p>So the walk declares a role first and the three writes are asserted against it in one
     * application's lifetime, which is what makes the two negative cases non-vacuous: the same fixture
     * that proves absent and blank <b>do not</b> change the field proves a named discipline <b>does</b>.
     *
     * <table>
     *   <caption>The halves of the rule asserted here</caption>
     *   <tr><th>body</th><th>stored authority</th></tr>
     *   <tr><td>{@code {"agreed":true}}</td><td>unchanged</td></tr>
     *   <tr><td>{@code {"agreed":true,"authority":""}}</td><td>unchanged — blank is not named</td></tr>
     *   <tr><td>{@code {"agreed":true,"authority":"ROLE_PARAMEDIC"}}</td><td>changed</td></tr>
     * </table>
     *
     * <p>⚠ The two remaining halves live where their subjects already were: an invalid value is still
     * a 400 that changes nothing ({@link #anAuthorityOutsideTheEightIsRefusedOnEveryWritePath}) and an
     * applicant who never declared one is still refused at Submit
     * ({@link #aSubmissionNamingNoAuthorityIsRefused}).
     *
     * <p>⚠ <b>An explicit {@code "authority": null} is not asserted because it is not
     * distinguishable</b> — {@link ApplicationConsentRequest} is a record, so after binding it is the
     * same value as an omitted key, and it is therefore a no-op rather than a clear. That limit is
     * recorded on the record itself.
     *
     * <p>The create's half is kept: a body naming nothing answers <b>201 with {@code authority}
     * null</b>, which is unchanged and is what {@code careers-handoff-contract.md} needs — the client
     * <b>drops</b> an unknown {@code ?track=} rather than raising, <i>"and the page still works with
     * no parameters at all"</i>.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSaveStoresWhatItNamesAndDoesNotBlankWhatItDoesNot() throws Exception {
        profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));

        // The create tolerates a body naming nothing, and stores null: unchanged by this decision.
        restMockMvc
            .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(CONSENT_WITHOUT_AUTHORITY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.authority").doesNotExist());
        assertThat(storedAuthority()).as("the create stores no authority when the body names none").isNull();

        // Declare one. The application has no documents, so every write below stays in
        // APPLICATION_STARTED and the status cannot mask a change to the authority.
        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authority").value("ROLE_NURSE"));
        assertThat(storedAuthority()).isEqualTo("ROLE_NURSE");

        // 1. A Save naming no authority leaves it in place — and still answers 200.
        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT_WITHOUT_AUTHORITY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authority").value("ROLE_NURSE"));
        assertThat(storedAuthority()).as("a Save naming no authority does not blank the stored role").isEqualTo("ROLE_NURSE");

        // 2. A blank one is the same thing, by the hasText the submission gate already uses.
        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT_WITH_BLANK_AUTHORITY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authority").value("ROLE_NURSE"));
        assertThat(storedAuthority()).as("blank is not named either").isEqualTo("ROLE_NURSE");

        // 3. And a Save that does name a different discipline changes it, which is what makes the two
        // assertions above assertions rather than a frozen field.
        restMockMvc
            .perform(
                put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content("{\"authority\":\"ROLE_PARAMEDIC\",\"agreed\":true}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authority").value("ROLE_PARAMEDIC"));
        assertThat(storedAuthority()).as("a named discipline is stored").isEqualTo("ROLE_PARAMEDIC");

        // And the new value is no more blankable than the first.
        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT_WITHOUT_AUTHORITY))
            .andExpect(status().isOk());
        assertThat(storedAuthority()).isEqualTo("ROLE_PARAMEDIC");

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getStatus())
            .as("and none of the six writes advanced an application with no documents")
            .isEqualTo(ProfileStatus.APPLICATION_STARTED);
    }

    // --- F-F: `source` belongs to the create ------------------------------------------------------

    /**
     * ⛔ <b>Save and Submit refuse a {@code source} rather than dropping it, and the refusal names the
     * endpoint that owns it (F-F).</b>
     *
     * <p>{@link ApplicationConsentRequest} is bound by all three writes and only the create passes
     * the component on, so before F-F a client sending one got <b>200 with the field ignored</b> —
     * the silence {@code ProfileFieldOwnership} exists to refuse, one file along.
     *
     * <p>Both paths asserted, because "the gate is on the shared method" is not true of this one: the
     * refusal is per handler, since the create is the handler that legitimately accepts the component.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void neitherLaterWriteAcceptsACareersAttribution() throws Exception {
        readyToSubmit();
        String withSource = "{\"authority\":\"ROLE_NURSE\",\"agreed\":true,\"source\":\"web-careers\"}";

        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(withSource))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("source is set by POST " + BASE)));
        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(withSource))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("source")));

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getStatus())
            .as("and the refusal happens before the transition")
            .isEqualTo(ProfileStatus.PROFILE_COMPLETED);
    }

    /**
     * ⭐ <b>The attribution the create recorded survives a Save that does not mention it.</b>
     *
     * <p>The half of F-F that the refusal exists to protect: a clinician who arrived through
     * {@code web.abofonsa.com/careers} must still read as {@code web-careers} in the review queue and
     * the WP7 funnel count after step 4's write. Asserted positively rather than inferred from the
     * refusal, because "the service has no {@code source} parameter" is a fact about today's code and
     * this is a fact about the data.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSaveDoesNotBlankTheAttributionTheCreateRecorded() throws Exception {
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));
        restMockMvc
            .perform(
                post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true,\"source\":\"web-careers\"}")
            )
            .andExpect(status().isCreated());
        for (PersonalDocument document : CompleteOnboardingFixture.mandatoryDocuments(profile)) {
            personalDocumentRepository.save(document);
        }
        restMockMvc.perform(put(BASE + "/me/complete-profile")).andExpect(status().isOk());

        restMockMvc
            .perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("web-careers"));

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getSource()).isEqualTo("web-careers");
    }

    /**
     * ⚠ <b>An applicant with no profile at all is told what is missing rather than met with a 409.</b>
     *
     * <p>{@code documentsFor(application)} raises {@code CONFLICT} <i>"Application has no linked
     * profile"</i>, which is what the old gate hit first for this caller — a refusal naming an
     * internal linkage rather than the four things the applicant has to go and do. The gate resolves
     * documents from the profile now, so all seven visible requirements are reported at once.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void anApplicantWithNoProfileIsToldEveryRequirement() throws Exception {
        readyToSubmit();
        profileRepository.deleteAll();

        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("profile")))
            .andExpect(jsonPath("$.detail").value(containsString("address")))
            .andExpect(jsonPath("$.detail").value(containsString("nextOfKin")))
            .andExpect(jsonPath("$.detail").value(containsString("certificate")))
            .andExpect(jsonPath("$.detail").value(containsString("license")))
            .andExpect(jsonPath("$.detail").value(containsString("identity")))
            .andExpect(jsonPath("$.detail").value(containsString("photo")));
    }

    /** And consent cannot be withdrawn into the write: {@code agreed: false} is 400 on both paths. */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void neitherWriteAcceptsAWithheldConsent() throws Exception {
        readyToSubmit();
        String withheld = "{\"authority\":\"ROLE_NURSE\",\"agreed\":false}";

        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(withheld)).andExpect(status().isBadRequest());
        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(withheld))
            .andExpect(status().isBadRequest());
    }

    /**
     * ⚠ <b>The consent date is not re-stamped by a later write.</b>
     *
     * <p>{@code profile.md} § "Consent statement" renders <i>"dated {@code
     * Application.agreedDate}"</i>, so moving the date on every Save would make the page state
     * something untrue about when the subject agreed. A re-affirmation of a consent already given is
     * not a new consent.
     *
     * <p>⭐ <b>Asserted across the full Save → Save → Submit sequence since the owner's decision of
     * 2026-10-09</b>, which is what makes this case bite: with Save non-advancing and repeatable,
     * step 4 is now a path a clinician walks several times before finishing, and a date re-stamped by
     * any one of those writes would be the page quietly restating when consent was given. It used to
     * exercise a single Submit.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void noLaterWriteInTheSequenceMovesTheConsentDate() throws Exception {
        Profile profile = readyToSubmit();
        Instant atCreation = applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getAgreedDate();

        // A Save while incomplete, a second one, then a Save and a Submit once complete — every
        // write step 4 has.
        profileRepository.save(profile.phoneNumber(null));
        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        profileRepository.save(profile.phoneNumber("+233300000000"));
        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isConflict());

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getAgreedDate()).isEqualTo(atCreation);
    }

    /**
     * ⛔ <b>A second <em>Submit</em> is the state machine's 409, not a silent second pass.</b>
     *
     * <p>{@code PROFILE_COMPLETED → CREDENTIAL_REVIEW} is the only legal move out, so an application
     * already in review has nowhere to go. Pinned because the alternative — tolerating it as a no-op
     * — would be this endpoint deciding transition legality, which is the one thing
     * {@code OnboardingService} exists to keep away from clients.
     *
     * <p>⚠ <b>This case asserted the same 409 for {@code PUT /me} until the owner's decision of
     * 2026-10-09, and its subject has changed rather than its assertion being wrong.</b> Save is now
     * required to be repeatable, so <em>it</em> answers 200 on a second call (see
     * {@link #aSaveCanBeCalledTwice}) and the conflict belongs here: Submit means "I am finished", and
     * saying that twice is a conflict in a way that saving twice is not.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSecondSubmitIsRefusedByTheStateMachine() throws Exception {
        readyToSubmit();
        restMockMvc.perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isConflict());
    }

    // --- The migrated surface answers on the new base --------------------------------------------

    /**
     * ⚠ <b>{@code /me} and {@code /compliance} are literal segments competing with
     * {@code /&#123;id&#125;} on one base, and that is a claim about Spring's pattern matching that no
     * amount of reading the mappings settles.</b>
     *
     * <p>If {@code GET /{id}} won over {@code GET /me}, the applicant's own read would become a
     * lookup of an application whose id is the string {@code "me"} — a 404 that looks exactly like
     * "you have not applied". So the discriminator is a successful read of a <em>real</em>
     * application through {@code /me}, which only the literal route can produce.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void theLiteralSegmentsWinOverTheIdTemplate() throws Exception {
        restMockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isCreated());

        restMockMvc.perform(get(BASE + "/me")).andExpect(status().isOk()).andExpect(jsonPath("$.authority").value("ROLE_NURSE"));
    }

    /** The compliance surface answers on the new base, for an administrator. */
    @Test
    @WithMockGatewayUser(authorities = { "ROLE_ADMIN" })
    void theComplianceSurfaceAnswersOnTheNewBase() throws Exception {
        restMockMvc.perform(get(BASE + "/compliance/metrics")).andExpect(status().isOk());
        restMockMvc.perform(get(BASE + "/compliance/expiring")).andExpect(status().isOk());
        restMockMvc.perform(get(BASE + "/compliance/events")).andExpect(status().isOk());
        restMockMvc.perform(post(BASE + "/compliance/sweep")).andExpect(status().isOk());
    }

    /** And the review queue does, reading the renamed field out on the wire. */
    @Test
    @WithMockGatewayUser(authorities = { "ROLE_ADMIN" })
    void theReviewQueueAnswersOnTheNewBase() throws Exception {
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication("t3-queued", ProfileStatus.CREDENTIAL_REVIEW).authority("ROLE_DOCTOR")
        );
        restMockMvc
            .perform(get(BASE).param("status", "CREDENTIAL_REVIEW"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].authority").value("ROLE_DOCTOR"))
            .andExpect(jsonPath("$[0].agreed").value(true));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * An application in {@code PROFILE_COMPLETED} with the four mandatory documents in place —
     * everything step 4's write needs except the write itself.
     *
     * <p>Built through the endpoints and {@link CompleteOnboardingFixture} rather than by writing a
     * {@code CREDENTIAL_REVIEW} row directly, so the walk that reaches the subject is the walk a
     * clinician takes.
     */
    private Profile readyToSubmit() throws Exception {
        return readyToSubmit(CONSENT);
    }

    /**
     * The authority as <b>stored</b>, re-read from the repository.
     *
     * <p>Read back rather than taken from the response, because the claim the owner's decision makes
     * is about what is kept: a handler returning the request's view of the application would satisfy
     * every {@code jsonPath} above while the document held something else.
     */
    private String storedAuthority() {
        return applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getAuthority();
    }

    /**
     * The same walk with the create's body named by the caller.
     *
     * <p>Parameterised since the owner's <i>"don't blank it"</i> decision: step 4's requirement is
     * now evaluated against the authority the application <b>holds</b>, so <em>whether one was ever
     * declared</em> is the premise of two cases — {@link #aSubmissionNamingNoAuthorityIsRefused}
     * passes {@code CONSENT_WITHOUT_AUTHORITY} and {@link #aSubmitNamingNoAuthorityUsesTheStoredRole}
     * passes {@code CONSENT}. The default is unchanged for every other caller.
     */
    private Profile readyToSubmit(String consent) throws Exception {
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));
        restMockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(consent)).andExpect(status().isCreated());
        for (PersonalDocument document : CompleteOnboardingFixture.mandatoryDocuments(profile)) {
            personalDocumentRepository.save(document);
        }
        restMockMvc.perform(put(BASE + "/me/complete-profile")).andExpect(status().isOk());
        // Returned since F-B: the cases that prove the submit gate work by taking this complete
        // profile and removing exactly one requirement from it, which is only possible if the caller
        // holds the saved row. The walk itself is unchanged.
        return profile;
    }
}
