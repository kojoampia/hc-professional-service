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
     * ⭐ <b>Step 4's write stores the consent and the authority and sets {@code CREDENTIAL_REVIEW} —
     * and it appends an {@code OnboardingEvent} doing it.</b>
     *
     * <p>The event is the half that cannot be read off the response, and it is the point of the
     * owner's instruction that the status write go through {@code OnboardingService.transition()}
     * rather than an assignment: a write that checks nothing and appends nothing leaves an
     * application whose own history does not contain the step. So this asserts the status <em>and</em>
     * that the trail gained a {@code PROFILE_COMPLETED → CREDENTIAL_REVIEW} row.
     *
     * <p>The authority sent here differs from the one the application was created with, which is
     * what makes "stores the requested authority" an assertion rather than a tautology.
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
     * ⭐ <b>Submit is the same operation under the path the old mapping migrated to.</b>
     *
     * <p>{@code profile.md} step 4 attributes identical effects to both buttons, so there is one
     * service method and two mappings — see {@link OnboardingService#submitForReview} for why a
     * Kafka-less Save variant was refused. Asserted as a <em>pair</em>, because "the two cannot
     * drift" is the claim and it is only worth anything if both paths are exercised.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void submitDoesWhatSaveDoes() throws Exception {
        readyToSubmit();

        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CREDENTIAL_REVIEW"))
            .andExpect(jsonPath("$.authority").value("ROLE_NURSE"));
    }

    /**
     * ⛔ <b>Neither write is a way past the mandatory-document gate.</b>
     *
     * <p>{@code profile.md} conditions the move on <i>"when all requirements are satisfied"</i>
     * without naming a button, so the gate is on the shared method and both paths meet it. This is
     * the case that would have gone quiet had Save been built as the ungated variant: an applicant
     * with no licence on file would have reached the review queue.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void neitherWriteSkipsTheMandatoryDocuments() throws Exception {
        readyToSubmit();
        personalDocumentRepository.deleteAll();

        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isBadRequest());
        restMockMvc
            .perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT))
            .andExpect(status().isBadRequest());
        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getStatus()).isEqualTo(
            ProfileStatus.PROFILE_COMPLETED
        );
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
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aLaterWriteDoesNotMoveTheConsentDate() throws Exception {
        readyToSubmit();
        Instant atCreation = applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getAgreedDate();

        restMockMvc.perform(put(BASE + "/me/submit").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());

        assertThat(applicationRepository.findByAccountId(accountIdFor(APPLICANT)).orElseThrow().getAgreedDate()).isEqualTo(atCreation);
    }

    /**
     * ⛔ <b>A second write is the state machine's 409, not a silent second pass.</b>
     *
     * <p>{@code PROFILE_COMPLETED → CREDENTIAL_REVIEW} is the only legal move out, so an application
     * already in review has nowhere to go. Pinned because the alternative — tolerating it as a
     * no-op — would be this endpoint deciding transition legality, which is the one thing
     * {@code OnboardingService} exists to keep away from clients.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aSecondWriteIsRefusedByTheStateMachine() throws Exception {
        readyToSubmit();
        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        restMockMvc.perform(put(BASE + "/me").contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isConflict());
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
    private void readyToSubmit() throws Exception {
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));
        restMockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isCreated());
        for (PersonalDocument document : CompleteOnboardingFixture.mandatoryDocuments(profile)) {
            personalDocumentRepository.save(document);
        }
        restMockMvc.perform(put(BASE + "/me/complete-profile")).andExpect(status().isOk());
    }
}
