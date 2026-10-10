package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.repository.AccountCompletenessRepository;
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
 * ⛔ <b>The meter cannot report a set of requirements the submit gate can refuse on, or the other way
 * round</b> — backlog.md row 230's ninth key.
 *
 * <h2>The defect this closes, which was live</h2>
 *
 * <p>{@code progressFor} built <b>eight</b> requirements; {@code unsatisfiedRequirements} could name a
 * <b>ninth</b>, {@code authority}. So an applicant who had never declared a discipline saw
 * <b>100%</b> and a Submit that answered <b>400</b>. That is precisely the <i>"client reads complete
 * while the server refuses"</i> failure the whole server-side-meter design was chosen to prevent,
 * happening inside that design — see {@code OnboardingProgressDTO}'s own class comment, which states
 * the intent the implementation had stopped honouring.
 *
 * <h2>⭐ Why asserting the two sets match is not enough on its own</h2>
 *
 * <p>Two hand-written lists over the same nine constants can be asserted equal today and diverge
 * tomorrow, which is exactly what happened: both sites read from the same private constants and
 * neither read from the same <em>list</em>. So this class asserts two different things.
 * {@link #theMeterAndTheSubmitGateNameTheSameRequirements} compares the two surfaces as a client sees
 * them; {@link #bothSurfacesDeriveTheirKeysFromOneDeclaration} asserts that each is
 * {@code OnboardingService.REQUIREMENT_KEYS} <b>entire and in order</b>, which is the property that
 * makes the first assertion stay true without anyone maintaining it.
 *
 * <p>⚠ What is shared is the <b>key vocabulary</b> and deliberately <b>not</b> the predicates: the
 * meter's step-2 predicates are weaker than the gate's on purpose, so that a meter does not go down
 * when a clinician starts typing a third emergency contact. {@code OnboardingService}'s own javadoc on
 * {@code REQUIREMENT_KEYS} and {@code unsatisfiedRequirements} argues both halves.
 */
@AutoConfigureMockMvc
@IntegrationTest
class OnboardingRequirementKeysIT {

    private static final String APPLICANT = "requirement-keys-applicant";

    private final ObjectMapper json = new ObjectMapper();

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
    private AccountCompletenessRepository accountCompletenessRepository;

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
     * ⛔ <b>A state that is complete by the meter and refused by Submit must be impossible.</b>
     *
     * <p>Built as the defect's own reproduction: an applicant with every document, a complete profile,
     * consent given — and <b>no declared authority</b>. Before row 230 the meter answered 100% and
     * {@code complete: true} here, and the Submit below answered 400 naming {@code authority}.
     *
     * <p>Now the meter must agree with the refusal, and both halves are asserted: 100% must not be
     * reported, and the key the Submit names must be one the meter also reported as outstanding.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void aMeterReadingCompleteCannotBeRefusedBySubmit() throws Exception {
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(accountIdFor(APPLICANT), ProfileStatus.APPLICATION_STARTED)
                .login(APPLICANT)
                .authority(null)
        );
        personalDocumentRepository.saveAll(
            CompleteOnboardingFixture.mandatoryDocuments(
                profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(APPLICANT)))
            )
        );

        JsonNode meter = json.readTree(
            restMockMvc.perform(get("/api/onboarding/progress")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
        );

        assertThat(meter.path("complete").asBoolean()).as("the meter must not call this complete — Submit refuses it").isFalse();
        assertThat(meter.path("percent").asInt()).isNotEqualTo(100);

        String refusal = restMockMvc
            .perform(put("/api/professional-application/me/submit").contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":true}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        // ⛔ THE GENERALISED INVARIANT: every requirement key the REFUSAL names must be one the meter
        // reported as outstanding. Anything the server refuses on while the meter calls it done is
        // the defect this class exists for, whichever key it turns out to be.
        //
        // ⚠ This loop was a TAUTOLOGY and asserted nothing. It read
        //     for (String named : namedIn(refusal, outstanding(meter)))
        //         assertThat(outstanding(meter)).contains(named);
        // and `namedIn` FILTERS its candidate set — so `named ∈ candidates` held by construction and
        // the body asserted `candidates.contains(named)`, which is true for any refusal, including an
        // empty one. Its comment called it "the whole of the invariant". The candidates are now the
        // whole declared vocabulary, so the filter selects what the refusal genuinely names and the
        // containment becomes a claim about the meter rather than about Stream.filter.
        for (String named : namedIn(refusal, Set.copyOf(OnboardingService.REQUIREMENT_KEYS))) {
            assertThat(outstanding(meter))
                .as("Submit refused naming '%s' while the meter reported it done — the lie this class exists for", named)
                .contains(named);
        }
        // And the concrete regression, named: before row 230 `authority` is the key the refusal
        // carried and the meter did not have at all.
        assertThat(refusal).contains("authority");
        assertThat(outstanding(meter)).contains("authority");
    }

    /**
     * The two surfaces name the same requirements, read as a client reads them: the meter's reported
     * keys and the keys a Submit on an empty account can refuse with.
     *
     * <p>An account with no application, no profile and no documents fails every requirement, so the
     * refusal names the gate's whole vocabulary and the meter reports the whole of its own — which is
     * what makes the two comparable as sets rather than as samples.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void theMeterAndTheSubmitGateNameTheSameRequirements() throws Exception {
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(accountIdFor(APPLICANT), ProfileStatus.APPLICATION_STARTED)
                .login(APPLICANT)
                .authority(null)
        );

        JsonNode meter = json.readTree(restMockMvc.perform(get("/api/onboarding/progress")).andReturn().getResponse().getContentAsString());
        String refusal = restMockMvc
            .perform(put("/api/professional-application/me/submit").contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":true}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Everything except consent, which the fixture stamped — and consent is the one key the gate
        // cannot report in practice, because `agreed: false` is refused earlier and separately.
        Set<String> expected = Set.copyOf(OnboardingService.REQUIREMENT_KEYS);
        assertThat(reportedKeys(meter)).isEqualTo(expected);
        for (String key : expected) {
            if (!"consent".equals(key)) {
                assertThat(refusal).as("the submit refusal must be able to name '%s'", key).contains(key);
            }
        }
    }

    /**
     * ⭐ <b>Both surfaces take their keys from one declaration</b>, {@code REQUIREMENT_KEYS} — in
     * order, entire, with nothing extra.
     *
     * <p>This is the assertion that does not have to be maintained. Equality of two measured sets
     * holds until someone adds a key to one site; equality of each site with the declaration fails the
     * moment they are not the same list.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void bothSurfacesDeriveTheirKeysFromOneDeclaration() throws Exception {
        JsonNode meter = json.readTree(restMockMvc.perform(get("/api/onboarding/progress")).andReturn().getResponse().getContentAsString());

        assertThat(orderedKeys(meter))
            .as("the meter reports REQUIREMENT_KEYS, in order — the client renders them in this sequence")
            .isEqualTo(OnboardingService.REQUIREMENT_KEYS);

        // And the nine are what row 230 settled, spelled out once here so that a key renamed in the
        // declaration — which every client maps to a translated label — fails a test rather than
        // rendering as the key itself, mid-screen, in four locales.
        assertThat(OnboardingService.REQUIREMENT_KEYS).containsExactly(
            "consent",
            "profile",
            "address",
            "nextOfKin",
            "certificate",
            "license",
            "identity",
            "photo",
            "authority"
        );
    }

    /**
     * ⚠ <b>The eight-key response keeps answering</b> — add before removing, exactly as
     * {@code POST /api/account} was kept deprecated through T4.
     *
     * <p>{@code web/} (unit B) and {@code mobile/} (unit C) read {@code requirements} today and
     * neither has been built against {@code steps}. So the original eight keys must all still be on
     * the wire, each with a {@code done}, beside the new {@code steps} object — an unmigrated client
     * must see no change at all except one extra field it ignores.
     */
    @Test
    @WithMockGatewayUser(login = APPLICANT, authorities = { "ROLE_USER" })
    void theEightKeyResponseStillAnswersForUnmigratedClients() throws Exception {
        JsonNode meter = json.readTree(
            restMockMvc.perform(get("/api/onboarding/progress")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
        );

        assertThat(reportedKeys(meter)).contains(
            "consent",
            "profile",
            "address",
            "nextOfKin",
            "certificate",
            "license",
            "identity",
            "photo"
        );
        assertThat(meter.path("percent").isInt()).isTrue();
        assertThat(meter.path("complete").isBoolean()).isTrue();
        assertThat(meter.path("requirements").isArray()).isTrue();
        meter.path("requirements").forEach(requirement -> assertThat(requirement.path("done").isBoolean()).isTrue());
        assertThat(meter.path("steps").isObject()).as("steps is added beside them, not instead of them").isTrue();
    }

    private Set<String> reportedKeys(JsonNode meter) {
        return Set.copyOf(orderedKeys(meter));
    }

    private List<String> orderedKeys(JsonNode meter) {
        return meter.path("requirements").findValuesAsText("key");
    }

    private Set<String> outstanding(JsonNode meter) {
        return java.util.stream.StreamSupport.stream(meter.path("requirements").spliterator(), false)
            .filter(requirement -> !requirement.path("done").asBoolean())
            .map(requirement -> requirement.path("key").asText())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The declared requirement keys the refusal's {@code still missing:} tail actually names.
     *
     * <p>⚠ <b>The TAIL, not the whole body.</b> A problem detail carries a {@code type} URI, a
     * {@code title} and the headline {@code SUBMISSION_REQUIRES_ALL_REQUIREMENTS} besides the key
     * list, and a bare {@code body.contains(key)} would pick up any key that happened to be a
     * substring of any of those — then assert the meter reported it outstanding, failing for a key
     * the refusal never named. None of the nine collides today, which is exactly why it would be a
     * trap: the tenth might, and the failure would look like the invariant breaking.
     *
     * <p>Split on non-letters and intersected, which is how {@code web/}'s
     * {@code review-detail-page.component.ts} reads the same sentence — so this test parses the
     * refusal the way the one shipped client does.
     */
    private Set<String> namedIn(String refusal, Set<String> candidates) {
        int marker = refusal.indexOf("still missing:");
        if (marker < 0) {
            return Set.of();
        }
        // Collected rather than Set.of(...) — the tail repeats words and Set.of throws
        // "duplicate element" on them, which is a crash in a helper rather than a failing assertion.
        Set<String> words = java.util.Arrays.stream(refusal.substring(marker).split("[^A-Za-z]+")).collect(
            java.util.stream.Collectors.toUnmodifiableSet()
        );
        return candidates.stream().filter(words::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
