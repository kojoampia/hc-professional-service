package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * The completion contract (professional-onboarding-workflow.md § "Onboarding state events and the
 * completion contract"): <b>nine</b> equally weighted requirements computed by the server, four
 * coarse steps derived from them, and a transition to {@code ACTIVE} that refuses an incomplete
 * profile however it is reached.
 *
 * <p>Server-side is the whole point. A client-side percentage can read 100% while the service still
 * refuses to advance the application, so these assert the figure the {@code ACTIVE} gate and the
 * post-sign-in redirect both read.
 *
 * <h2>⭐ Nine since backlog.md row 230, and the arithmetic below is the evidence</h2>
 *
 * <p>The meter built <b>eight</b> requirements while {@code unsatisfiedRequirements} could refuse a
 * submission naming a <b>ninth</b>, {@code authority} — so an applicant could read 100% here and be
 * refused at Submit, which is the <i>"client reads complete while the server refuses"</i> failure
 * the whole design was chosen to prevent, happening inside it.
 *
 * <p><b>The percentages in {@link #gradesEachRequirementAsItIsSatisfied} are the test of that.</b>
 * They read 13 / 50 / 100 over eight and now read 22 / 56 / 100 over nine: a denominator cannot be
 * asserted directly, so the figures are what fail if the key set silently returns to eight.
 * {@link OnboardingRequirementKeysIT} asserts the set itself and that the meter and the submit gate
 * take it from one declaration.
 */
@AutoConfigureMockMvc
@IntegrationTest
class OnboardingProgressIT {

    private static final String APPLICANT = "progress-applicant";
    private static final String ADMIN = "progress-admin";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private OnboardingEventRepository eventRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private net.jojoaddison.repository.AccountCompletenessRepository accountCompletenessRepository;

    /**
     * Injected for {@code recordAccountCompleteness} alone — the step-1 projection's only writer. The
     * frame-to-call half is {@code MeterConsumerUnitTest}'s; what these cases assert is that the
     * {@code GET} reads what it wrote.
     */
    @Autowired
    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void cleanup() {
        applicationRepository.deleteAll();
        eventRepository.deleteAll();
        profileRepository.deleteAll();
        personalDocumentRepository.deleteAll();
        accountCompletenessRepository.deleteAll();
    }

    /**
     * An account created by admin invitation has a login before it has anything else. The profile
     * page has to render a meter for that person, so this answers rather than 404s.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void reportsZeroForAnAccountWithNoApplicationAtAll() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.percent").value(0))
            .andExpect(jsonPath("$.complete").value(false))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.requirements.length()").value(9))
            // Four steps, all outstanding. Step 1 is false here for the ordinary reason as well as
            // the obvious one: no AccountDetailsUpdated frame has arrived for this account, and
            // "not known to be complete" is reported as outstanding rather than assumed.
            .andExpect(jsonPath("$.steps.account").value(false))
            .andExpect(jsonPath("$.steps.profile").value(false))
            .andExpect(jsonPath("$.steps.documents").value(false))
            .andExpect(jsonPath("$.steps.consent").value(false));
    }

    /**
     * `complete` and `status` answer different questions and the shell's first-login interstitial
     * depends on the difference: completeness is the applicant's half, while ACTIVE additionally
     * requires admin vetting. A finished profile nobody has reviewed is therefore complete and a
     * long way from ACTIVE, and anything gating on `complete` would treat it as live.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void reportsTheApplicationStatusAlongsideCompleteness() throws Exception {
        ProfessionalApplication application = startedApplication();
        Profile saved = profileRepository.save(completeProfile());
        uploadAllMandatoryDocuments(saved);

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.status").value(application.getStatus().name()))
            .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.not("ACTIVE")));
    }

    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void gradesEachRequirementAsItIsSatisfied() throws Exception {
        ProfessionalApplication application = startedApplication();

        // Consent and the declared authority — step 4's two halves: 2 of 9 -> 22%. This read 13%
        // over eight requirements until row 230 added the ninth.
        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.percent").value(22))
            .andExpect(jsonPath("$.requirements[?(@.key=='authority')].done").value(true))
            .andExpect(jsonPath("$.steps.consent").value(true));

        Profile saved = profileRepository.save(completeProfile());

        // + personal details + address + next of kin: 5 of 9 -> 56%.
        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.percent").value(56))
            .andExpect(jsonPath("$.complete").value(false))
            .andExpect(jsonPath("$.requirements[?(@.key=='profile')].done").value(true))
            .andExpect(jsonPath("$.requirements[?(@.key=='nextOfKin')].done").value(true))
            .andExpect(jsonPath("$.requirements[?(@.key=='certificate')].done").value(false));

        uploadAllMandatoryDocuments(saved);

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.percent").value(100))
            .andExpect(jsonPath("$.complete").value(true))
            // ⚠ 100% and complete with step 1 still outstanding, deliberately: `complete` is over the
            // nine requirements this service can enforce and step 1 is reported, not required. See
            // OnboardingProgressDTO.Steps — folding it in would mean no clinician could be activated
            // while the broker was absent.
            .andExpect(jsonPath("$.steps.account").value(false))
            .andExpect(jsonPath("$.steps.profile").value(true))
            .andExpect(jsonPath("$.steps.documents").value(true))
            .andExpect(jsonPath("$.steps.consent").value(true));
    }

    /**
     * ⛔ <b>Step 3 is all four mandatory documents, not any of them.</b>
     *
     * <p>Each document is added one at a time and {@code steps.documents} must stay false until the
     * fourth lands. The failure this refuses is concrete: a step-3 pane reading complete after one
     * upload shows the applicant a finished step behind a Submit that answers 400 naming the three
     * documents they have not provided.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void doesNotCompleteStepThreeUntilAllFourDocumentsArePresent() throws Exception {
        startedApplication();
        Profile saved = profileRepository.save(completeProfile());

        personalDocumentRepository.save(CompleteOnboardingFixture.document(saved, DocumentType.CERTIFICATE, null));
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.documents").value(false));

        personalDocumentRepository.save(
            CompleteOnboardingFixture.document(saved, DocumentType.LICENSE, CompleteOnboardingFixture.currentLicenseExpiry())
        );
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.documents").value(false));

        personalDocumentRepository.save(CompleteOnboardingFixture.document(saved, DocumentType.GHANACARD, null));
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.documents").value(false));

        personalDocumentRepository.save(CompleteOnboardingFixture.document(saved, DocumentType.PASSPHOTO, null));
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.documents").value(true));
    }

    /**
     * Step 2 needs all three of its requirements, and an aggregate percentage cannot say which of
     * them is wired to which — so the step is asserted against a profile that is complete except for
     * its contacts.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void doesNotCompleteStepTwoWhileAnyOfItsThreeRequirementsIsOutstanding() throws Exception {
        startedApplication();
        profileRepository.save(completeProfile().contacts(java.util.List.of()));

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.requirements[?(@.key=='profile')].done").value(true))
            .andExpect(jsonPath("$.requirements[?(@.key=='address')].done").value(true))
            .andExpect(jsonPath("$.requirements[?(@.key=='nextOfKin')].done").value(false))
            .andExpect(jsonPath("$.steps.profile").value(false));
    }

    /**
     * Step 4 is {@code profile.md}'s <i>"declares the role they are applying for <b>and</b>
     * consents"</i>, so both halves are required and an application carrying only the tick does not
     * complete it.
     *
     * <p>⭐ <b>This is the ninth key, asserted as the applicant experiences it.</b> Before row 230
     * {@code authority} was absent from the meter entirely, so this application reported the same
     * percentage as one that had declared a discipline — and the difference only appeared as a 400
     * from Submit.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void doesNotCompleteStepFourOnConsentAlone() throws Exception {
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(accountIdFor(APPLICANT), ProfileStatus.APPLICATION_STARTED)
                .login(APPLICANT)
                .authority(null)
        );

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.requirements[?(@.key=='consent')].done").value(true))
            .andExpect(jsonPath("$.requirements[?(@.key=='authority')].done").value(false))
            .andExpect(jsonPath("$.steps.consent").value(false))
            // 1 of 9. The point is that it is no longer 1 of 8 reported as 13% beside an application
            // that had declared a role.
            .andExpect(jsonPath("$.percent").value(11));
    }

    /**
     * Step 4's consent half, on its own: an application with a declared discipline and no consent.
     *
     * <p>Reached by writing the row rather than through the endpoint, because
     * {@code storeThenAdvanceWhenComplete} refuses {@code agreed: false} with its own 400 — so this
     * state is one an admin-invited application can be in and not one a Save can produce.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void doesNotCompleteStepFourWithoutConsent() throws Exception {
        applicationRepository.save(
            new ProfessionalApplication()
                .accountId(accountIdFor(APPLICANT))
                .status(ProfileStatus.APPLICATION_STARTED)
                .login(APPLICANT)
                .authority(CompleteOnboardingFixture.FIXTURE_AUTHORITY)
        );

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.requirements[?(@.key=='consent')].done").value(false))
            .andExpect(jsonPath("$.requirements[?(@.key=='authority')].done").value(true))
            .andExpect(jsonPath("$.steps.consent").value(false));
    }

    /**
     * ⭐ <b>Step 1 is the only step this service does not compute, and it moves when the gateway says
     * so.</b>
     *
     * <p>{@code recordAccountCompleteness} is called directly here rather than through the broker:
     * {@code MeterConsumerUnitTest} covers the frame-to-call half, and what this asserts is that the
     * <b>{@code GET} reads the projection</b> — the half row 230 insists on, because SSE delivers
     * changes after connecting and a client needs an initial value regardless.
     *
     * <p>⚠ It also asserts the verdict travelling <b>back down</b>. An account whose details are
     * cleared has an outstanding step 1 again, and a projection that only ever went true would be
     * indistinguishable from one that worked until the first correction.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void reportsStepOneFromTheGatewaysVerdict() throws Exception {
        startedApplication();

        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.account").value(false));

        onboardingService.recordAccountCompleteness(accountIdFor(APPLICANT), true, Instant.now());
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.account").value(true));

        onboardingService.recordAccountCompleteness(accountIdFor(APPLICANT), false, Instant.now().plusSeconds(1));
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.account").value(false));
    }

    /**
     * ⛔ <b>An observation older than the stored one is refused, not applied.</b>
     *
     * <p>At-least-once delivery is not at-least-once ordering, so a redelivered or reordered frame
     * can carry a verdict that has since been superseded. Applying it would move step 1 backwards and
     * leave it there until the account was next written — and nothing would log, because from the
     * consumer's side a stale frame looks exactly like a fresh one.
     *
     * <p>⚠ <b>This guard was argued in five javadocs and had no test.</b> Deleting the
     * {@code isAfter} refusal left 22 integration tests and 17 unit tests green: the case above only
     * ever calls the method with <em>increasing</em> timestamps, and {@code MeterConsumerUnitTest}
     * stubs the method out entirely. A guard whose only evidence is prose is not a guard.
     *
     * <p>The return value is asserted beside the projection because {@code MeterConsumer} reads it —
     * it pushes only when the projection moved, so a refusal that silently returned {@code true}
     * would turn a redelivery storm into a socket-write storm.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void refusesAnAccountObservationOlderThanTheStoredOne() throws Exception {
        startedApplication();
        Instant newer = Instant.parse("2026-10-10T10:00:00Z");

        assertThat(onboardingService.recordAccountCompleteness(accountIdFor(APPLICANT), false, newer)).isTrue();

        assertThat(onboardingService.recordAccountCompleteness(accountIdFor(APPLICANT), true, newer.minusSeconds(60)))
            .as("a stale observation must be refused rather than applied")
            .isFalse();
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.account").value(false));
        assertThat(accountCompletenessRepository.findById(accountIdFor(APPLICANT)).orElseThrow().getObservedAt()).isEqualTo(newer);

        // Equal timestamps ARE applied — an idempotent rewrite of the same answer, which is what a
        // plain redelivery is. Asserted so that tightening `isAfter` to `!isBefore` has a test in
        // front of it: that would refuse a redelivery the projection is documented to tolerate.
        assertThat(onboardingService.recordAccountCompleteness(accountIdFor(APPLICANT), true, newer)).isTrue();
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.account").value(true));
    }

    /**
     * ⛔ <b>A frame naming no account, or carrying no {@code occurredAt}, is refused outright.</b>
     *
     * <p>The second half is the one that matters, and it is not defensive tidying: a row whose
     * {@code observedAt} was stamped with the <em>local clock</em> could not be compared with the next
     * frame's send time, so <b>one such write would disable the ordering guard above for that account
     * permanently</b>. Refusing the frame is what keeps that guard's input honest.
     *
     * <p>⚠ Deleting this refusal left 33 tests green before this case existed.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void refusesAnAccountObservationThatNamesNobodyOrCarriesNoTimestamp() throws Exception {
        startedApplication();

        assertThat(onboardingService.recordAccountCompleteness(accountIdFor(APPLICANT), true, null))
            .as("no occurredAt — stamping the local clock would disable the ordering guard for this account")
            .isFalse();
        assertThat(onboardingService.recordAccountCompleteness(null, true, Instant.now())).isFalse();
        assertThat(onboardingService.recordAccountCompleteness("  ", true, Instant.now())).isFalse();

        assertThat(accountCompletenessRepository.count()).as("a refused observation must write nothing at all").isZero();
        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.steps.account").value(false));
    }

    /**
     * ⚠ <b>Step 1 does not count towards {@code percent} or {@code complete}</b>, so a satisfied
     * step 1 must not move either — see {@code OnboardingProgressDTO.Steps}. Asserted because the
     * obvious implementation of "report the fourth step" is to add a tenth requirement, and that one
     * change would make every activation in the estate depend on a Kafka frame.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void stepOneDoesNotCountTowardsThePercentage() throws Exception {
        startedApplication();

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.percent").value(22))
            .andExpect(jsonPath("$.requirements.length()").value(9));

        onboardingService.recordAccountCompleteness(accountIdFor(APPLICANT), true, Instant.now());

        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(jsonPath("$.percent").value(22))
            .andExpect(jsonPath("$.requirements.length()").value(9))
            .andExpect(jsonPath("$.requirements[?(@.key=='account')]").doesNotExist())
            .andExpect(jsonPath("$.steps.account").value(true));
    }

    /** A licence without an expiry date does not count — the compliance sweep has nothing to sweep. */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void doesNotCreditALicenceWithNoExpiryDate() throws Exception {
        startedApplication();
        Profile saved = profileRepository.save(completeProfile());
        personalDocumentRepository.save(CompleteOnboardingFixture.document(saved, DocumentType.LICENSE, null));

        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.requirements[?(@.key=='license')].done").value(false));

        personalDocumentRepository.save(
            CompleteOnboardingFixture.document(saved, DocumentType.LICENSE, CompleteOnboardingFixture.currentLicenseExpiry())
        );

        restMockMvc.perform(get("/api/onboarding/progress")).andExpect(jsonPath("$.requirements[?(@.key=='license')].done").value(true));
    }

    /**
     * The half of "active only when complete AND vetted" that the admin cannot override. Vetting is
     * the APPROVED -> ... -> ROSTER_CONFIGURED chain this fixture starts from; completeness is
     * checked here, so an admin activating an unfinished application gets a 409 naming what is
     * missing rather than a working account.
     */
    @Test
    @WithMockGatewayUser(login = ADMIN, authorities = { "ROLE_ADMIN" })
    void refusesToActivateAnIncompleteProfile() throws Exception {
        ProfessionalApplication application = applicationRepository.save(applicationIn(ProfileStatus.ROSTER_CONFIGURED));

        // The body is asserted, not just the status. A bare 409 outlives the reason it was written for:
        // any of the other refusals on this path — an illegal transition, a missing profile — returns
        // the same code, so a status-only assertion would keep passing while this test stopped being
        // about completeness at all. That is how backlog item 14 started.
        restMockMvc
            .perform(put("/api/professional-application/" + application.getId() + "/activate"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString(OnboardingService.ACTIVATION_REQUIRES_COMPLETE_PROFILE)));
    }

    @Test
    @WithMockGatewayUser(login = ADMIN, authorities = { "ROLE_ADMIN" })
    void activatesOnceEveryRequirementIsSatisfied() throws Exception {
        ProfessionalApplication application = applicationRepository.save(applicationIn(ProfileStatus.ROSTER_CONFIGURED));
        uploadAllMandatoryDocuments(profileRepository.save(completeProfile()));

        restMockMvc
            .perform(put("/api/professional-application/" + application.getId() + "/activate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(ProfileStatus.ACTIVE.name()));
    }

    private ProfessionalApplication startedApplication() {
        return applicationRepository.save(applicationIn(ProfileStatus.APPLICATION_STARTED));
    }

    /**
     * The application half of the contract — consent — plus the two fields this class's applicant
     * happens to carry. Nothing else is applied here: which of the remaining requirements a test
     * satisfies is the test's own business, and several deliberately satisfy none of them.
     */
    private ProfessionalApplication applicationIn(ProfileStatus status) {
        return CompleteOnboardingFixture.consentedApplication(accountIdFor(APPLICANT), status).login(APPLICANT).authority("ROLE_NURSE");
    }

    private Profile completeProfile() {
        return CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT));
    }

    private void uploadAllMandatoryDocuments(Profile profile) {
        personalDocumentRepository.saveAll(CompleteOnboardingFixture.mandatoryDocuments(profile));
    }
}
