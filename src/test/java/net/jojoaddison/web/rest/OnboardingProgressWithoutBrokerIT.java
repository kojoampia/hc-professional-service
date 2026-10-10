package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.config.AsyncSyncConfiguration;
import net.jojoaddison.config.EmbeddedMongo;
import net.jojoaddison.config.JacksonConfiguration;
import net.jojoaddison.config.TestJacksonConfiguration;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.repository.AccountCompletenessRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ⭐ <b>With no broker, the meter still answers, and answers correctly</b> — the whole reason
 * backlog.md row 230 keeps {@code GET /api/onboarding/progress} authoritative instead of letting the
 * event stream become the meter's source.
 *
 * <h2>The failure this refuses, which is this estate's signature one</h2>
 *
 * <p>{@code application.kafka.enabled=false} is a <b>supported configuration</b>, not a fault:
 * {@code DomainEventPublisher} returns before {@code streamBridge.send}, so no binding is created and
 * nothing retries, and the workspace guide states the rule it exists for — <i>a missing broker is
 * silent: the app starts, serves and reports healthy while everything produced goes nowhere.</i>
 *
 * <p><b>A meter fed only by events would therefore sit at a stale value, or at zero, with nothing
 * erroring and every health check green.</b> Row 230 names that outright as a consequence to build
 * against rather than rediscover. So steps 2, 3 and 4 are computed from this database on every
 * request, and the degradation with no broker is <b>"no live refresh"</b> rather than "no meter".
 *
 * <p>⚠ <b>Step 1 is the exception and it is the honest one.</b> Its four fields are on {@code User} in
 * the gateway and arrive only as an event, so with no broker it reads {@code false} — <i>not known to
 * be complete</i>. {@link #stepOneReadsOutstandingWithNoBrokerAndNothingElseIsAffected} asserts that
 * this costs exactly one boolean: {@code percent}, {@code complete} and the other three steps are
 * unaffected, which is why nothing in the service gates on it. See
 * {@code OnboardingProgressDTO.Steps}.
 *
 * <p>Its own context, with {@code @EmbeddedKafka} deliberately absent, because the property is about a
 * deployment that has no broker at all. {@code DocumentUploadLimitIT} is the precedent for that shape.
 */
@SpringBootTest(
    classes = {
        net.jojoaddison.ProfessionalServiceApp.class,
        JacksonConfiguration.class,
        TestJacksonConfiguration.class,
        AsyncSyncConfiguration.class,
    },
    properties = { "application.kafka.enabled=false" }
)
@EmbeddedMongo
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OnboardingProgressWithoutBrokerIT {

    private static final String APPLICANT = "no-broker-applicant";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private AccountCompletenessRepository accountCompletenessRepository;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void cleanup() {
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
        personalDocumentRepository.deleteAll();
        accountCompletenessRepository.deleteAll();
    }

    /**
     * Every requirement satisfied, no broker anywhere, and the meter reads 100% and
     * {@code complete: true} — because it is computed and not accumulated.
     */
    @Test
    void theMeterIsCorrectWithNoBrokerAtAll() throws Exception {
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(accountIdFor(APPLICANT), ProfileStatus.APPLICATION_STARTED).login(APPLICANT)
        );
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));
        personalDocumentRepository.saveAll(CompleteOnboardingFixture.mandatoryDocuments(profile));

        restMockMvc
            .perform(get("/api/onboarding/progress").with(gatewayUser(APPLICANT, "ROLE_USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.percent").value(100))
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.requirements.length()").value(9))
            .andExpect(jsonPath("$.steps.profile").value(true))
            .andExpect(jsonPath("$.steps.documents").value(true))
            .andExpect(jsonPath("$.steps.consent").value(true));
    }

    /**
     * An incomplete applicant is graded correctly too — not merely answered. A meter whose only input
     * was events would report zero here, which is indistinguishable from a brand-new applicant and is
     * the exact confusion row 230's second consequence names.
     */
    @Test
    void anIncompleteApplicantIsStillGradedRatherThanZeroed() throws Exception {
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(accountIdFor(APPLICANT), ProfileStatus.APPLICATION_STARTED).login(APPLICANT)
        );
        profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));

        restMockMvc
            .perform(get("/api/onboarding/progress").with(gatewayUser(APPLICANT, "ROLE_USER")))
            .andExpect(jsonPath("$.percent").value(56))
            .andExpect(jsonPath("$.steps.profile").value(true))
            .andExpect(jsonPath("$.steps.documents").value(false));
    }

    /**
     * ⚠ <b>Step 1 costs exactly one boolean when the broker is absent</b>, and that bound is the
     * reason it is reported rather than required.
     *
     * <p>With no frames arriving the projection is empty, so {@code steps.account} is {@code false}.
     * If it counted towards {@code complete}, this assertion would also show every activation in the
     * estate failing — {@code OnboardingService.requireCompleteProfile} reads {@code complete}, and
     * {@code publishProfileStatus} puts it on the wire to hc-admin.
     */
    @Test
    void stepOneReadsOutstandingWithNoBrokerAndNothingElseIsAffected() throws Exception {
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(accountIdFor(APPLICANT), ProfileStatus.APPLICATION_STARTED).login(APPLICANT)
        );
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)));
        personalDocumentRepository.saveAll(CompleteOnboardingFixture.mandatoryDocuments(profile));

        restMockMvc
            .perform(get("/api/onboarding/progress").with(gatewayUser(APPLICANT, "ROLE_USER")))
            .andExpect(jsonPath("$.steps.account").value(false))
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.percent").value(100));

        assertThat(accountCompletenessRepository.count()).as("no frames arrived, so nothing was projected").isZero();
    }

    /**
     * The stream still opens with no broker — it simply never receives anything. A client that
     * subscribes must not meet an error that reads as a broken endpoint; the degradation is silence.
     */
    @Test
    void theStreamStillOpensWithNoBroker() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/progress/stream").with(gatewayUser(APPLICANT, "ROLE_USER")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted());
    }
}
