package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.repository.AccountCompletenessRepository;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.OnboardingProgressStream;
import net.jojoaddison.service.OnboardingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ⛔ <b>A clinician's live meter stream is their own, and nothing a request carries can change
 * that</b> — backlog.md row 230, unit A, and row 226 for the precedent.
 *
 * <h2>Why this is the case that matters most in the unit</h2>
 *
 * <p>Row 226 — closed three hours before row 230 was written — was an unowned read of every
 * clinician's identity-document bytes by any clinical role, measured on the running quality stack. The
 * in-tree SSE precedent has the same shape twice over: {@code broker/KafkaConsumer.register(String
 * key)} files an emitter under whatever key it is handed, and its {@code accept} then <b>broadcasts to
 * every emitter in the map regardless of key</b>. On a progress meter either of those is one applicant
 * watching another's onboarding.
 *
 * <p>So the assertions below are not "the right person gets their frame" — they are <b>"the wrong
 * person gets nothing"</b>, which is a different test and the one that fails against an unscoped
 * implementation.
 *
 * <h2>How MockMvc holds an SSE stream</h2>
 *
 * <p>{@code SseEmitter} is an async return value, so the request starts async and the handler returns
 * before anything is written; {@code request().asyncStarted()} is the assertion that it did.
 * Subsequent {@code emitter.send} calls write straight into the {@code MockHttpServletResponse}, so
 * {@code getResponse().getContentAsString()} is what the client would have received so far.
 *
 * <h2>⛔ The emitters are NOT closed between tests, and the assertions are written for that</h2>
 *
 * <p>This javadoc used to promise they were completed in {@code cleanup()}. They were not — it only
 * deleted repositories — and the promise mattered, because {@code OnboardingProgressStream} is a
 * context singleton: an emitter opened here outlives the test that opened it. The security-critical
 * {@link #headIsGatedAndScopedExactlyLikeGet} counted the caller's open streams <b>absolutely</b>,
 * so it was green only because JUnit's default hash ordering ran it first; with
 * {@code @TestMethodOrder(MethodName.class)} and nothing else changed it failed
 * {@code expected: 1 but was: 3}.
 *
 * <p>⚠ <b>Closing them from here is not available, which is why the fix is in the assertions
 * instead.</b> {@code MvcResult.getAsyncResult()} does not hand back an {@code SseEmitter} — it
 * <em>blocks</em> waiting for an async result that a streaming response never sets, so a
 * {@code cleanup()} written that way times out after 15 seconds and completes nothing; measured, it
 * turned four passing cases into timeouts <em>and</em> left the leak in place. Reaching into the
 * registry would need a production method with no production caller.
 *
 * <p><b>So every count here is a DELTA.</b> Each case records the streams an account holds before it
 * acts and asserts the change, which is order-independent, says exactly what the endpoint did, and
 * cannot be disarmed by a seventh test method. {@code @TestMethodOrder} stays so that any future
 * failure of this class is reproducible rather than hash-dependent. The accumulation itself is
 * bounded by the number of cases and harms nothing: a push is routed by account, so a stale emitter
 * for another login can never receive it — which is the property under test in the first place.
 */
@AutoConfigureMockMvc
@IntegrationTest
// Deterministic, so a leak like the one this class shipped is reproducible rather than dependent on
// JUnit's hash ordering. Adding this annotation is what exposed it: headIsGatedAndScopedExactlyLikeGet
// counted three open streams instead of one, and had been green only because it happened to run first.
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
class OnboardingProgressStreamIT {

    private static final String AMA = "stream-ama";
    private static final String KOFI = "stream-kofi";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private OnboardingProgressStream progressStream;

    @Autowired
    private OnboardingService onboardingService;

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
     * ⛔ <b>The case the unit exists for.</b> Two authenticated clinicians each hold a stream; a push
     * for one reaches one.
     *
     * <p>Both halves are asserted, and the second is the one with teeth: an implementation that
     * broadcast — which is exactly what the in-tree precedent's {@code accept} does — would satisfy
     * "Ama received her meter" and fail only on "Kofi received nothing".
     */
    @Test
    void oneCallersPushNeverReachesAnother() throws Exception {
        applicationRepository.save(CompleteOnboardingFixture.consentedApplication(accountIdFor(AMA), ProfileStatus.APPLICATION_STARTED));

        MvcResult ama = openStream(AMA);
        MvcResult kofi = openStream(KOFI);

        progressStream.push(accountIdFor(AMA), onboardingService.progressFor(accountIdFor(AMA)));

        assertThat(ama.getResponse().getContentAsString())
            .as("the account the push named must receive it")
            .contains(OnboardingProgressStream.EVENT_NAME)
            .contains("\"percent\"");
        assertThat(kofi.getResponse().getContentAsString()).as("⛔ another clinician's meter must not arrive on this stream").isEmpty();
    }

    /**
     * ⛔ <b>Nothing a request carries can name a subject</b>, because the handler takes no subject at
     * all — no path variable, no parameter, no header is read.
     *
     * <p>Each attempt below is a shape somebody would reach for: a query parameter, a parameter under
     * the name the far side uses internally, and a header. All three must be <em>ignored</em> and the
     * caller's own stream returned; the fourth, a path segment, must not resolve to a handler at all.
     *
     * <p>⚠ The assertion is that Kofi's stream stays empty while Ama pushes, not merely that the
     * requests succeeded. A handler that read the parameter would answer 200 just the same — the leak
     * is in where the frames go, not in the status line.
     */
    @Test
    void noParameterHeaderOrPathSegmentCanSubscribeToAnotherAccount() throws Exception {
        applicationRepository.save(CompleteOnboardingFixture.consentedApplication(accountIdFor(AMA), ProfileStatus.APPLICATION_STARTED));

        MvcResult byParameter = restMockMvc
            .perform(get("/api/onboarding/progress/stream").param("accountId", accountIdFor(AMA)).with(gatewayUser(KOFI, "ROLE_USER")))
            .andExpect(request().asyncStarted())
            .andReturn();
        MvcResult byKey = restMockMvc
            .perform(get("/api/onboarding/progress/stream").param("key", accountIdFor(AMA)).with(gatewayUser(KOFI, "ROLE_USER")))
            .andExpect(request().asyncStarted())
            .andReturn();
        MvcResult byHeader = restMockMvc
            .perform(get("/api/onboarding/progress/stream").header("X-Account-Id", accountIdFor(AMA)).with(gatewayUser(KOFI, "ROLE_USER")))
            .andExpect(request().asyncStarted())
            .andReturn();

        // A subject in the path does not reach a handler: there is no such mapping, and adding one is
        // the change this case exists to make visible.
        restMockMvc
            .perform(get("/api/onboarding/progress/stream/" + accountIdFor(AMA)).with(gatewayUser(KOFI, "ROLE_USER")))
            .andExpect(status().isNotFound());

        progressStream.push(accountIdFor(AMA), onboardingService.progressFor(accountIdFor(AMA)));

        assertThat(byParameter.getResponse().getContentAsString()).as("a query parameter must not redirect the stream").isEmpty();
        assertThat(byKey.getResponse().getContentAsString()).as("neither must one named as the internal key").isEmpty();
        assertThat(byHeader.getResponse().getContentAsString()).as("nor a header").isEmpty();

        // And the three streams Kofi opened are Kofi's: pushing to him reaches all three, which is
        // what shows the requests were honoured rather than quietly discarded.
        progressStream.push(accountIdFor(KOFI), onboardingService.progressFor(accountIdFor(KOFI)));
        assertThat(byParameter.getResponse().getContentAsString()).contains(OnboardingProgressStream.EVENT_NAME);
        assertThat(byKey.getResponse().getContentAsString()).contains(OnboardingProgressStream.EVENT_NAME);
        assertThat(byHeader.getResponse().getContentAsString()).contains(OnboardingProgressStream.EVENT_NAME);
    }

    /**
     * One clinician, two tabs: both stay live.
     *
     * <p>The in-tree precedent's {@code Map<String, SseEmitter>} silently drops the first emitter when
     * a second registers under the same key, which here would leave one tab updating and the other
     * permanently stale with nothing logged.
     */
    @Test
    void aSecondStreamForTheSameAccountDoesNotDisplaceTheFirst() throws Exception {
        applicationRepository.save(CompleteOnboardingFixture.consentedApplication(accountIdFor(AMA), ProfileStatus.APPLICATION_STARTED));

        MvcResult firstTab = openStream(AMA);
        MvcResult secondTab = openStream(AMA);

        progressStream.push(accountIdFor(AMA), onboardingService.progressFor(accountIdFor(AMA)));

        assertThat(firstTab.getResponse().getContentAsString()).contains(OnboardingProgressStream.EVENT_NAME);
        assertThat(secondTab.getResponse().getContentAsString()).contains(OnboardingProgressStream.EVENT_NAME);
    }

    /**
     * ⚠ <b>{@code HEAD} beside {@code GET}, because Spring MVC dispatches a {@code HEAD} to a
     * {@code @GetMapping} handler</b> and this estate has shipped an authority rule scoped to
     * {@code HttpMethod.GET} that a {@code HEAD} fell straight through — twice, once on this
     * repository's own {@code ProfileResource} and once, suspected, on hc-admin's CSV export.
     *
     * <p>Nothing would leak here either way: the stream is resolved from the token, so a {@code HEAD}
     * reaches the caller's own and no one else's. What is asserted is that the <b>verb is covered by
     * the gate</b> — {@code /api/onboarding/**} is {@code .authenticated()} with no method scope — so
     * that narrowing that rule later fails here rather than in production. A body-less verb is not a
     * lesser read.
     */
    @Test
    void headIsGatedAndScopedExactlyLikeGet() throws Exception {
        int amaBefore = progressStream.openStreams(accountIdFor(AMA));
        int kofiBefore = progressStream.openStreams(accountIdFor(KOFI));

        restMockMvc.perform(head("/api/onboarding/progress/stream").with(gatewayUser(AMA, "ROLE_USER"))).andExpect(status().isOk());

        // DELTAS, not absolutes — see the class comment. The claim is "the HEAD opened exactly one
        // stream and it was the caller's", which is what the absolute count was trying to say and
        // could only say while it happened to run first.
        assertThat(progressStream.openStreams(accountIdFor(AMA))).as("the HEAD opened the caller's own stream").isEqualTo(amaBefore + 1);
        assertThat(progressStream.openStreams(accountIdFor(KOFI))).as("and nobody else's").isEqualTo(kofiBefore);
    }

    /** An unauthenticated caller has no account to resolve, so there is nothing to subscribe to. */
    @Test
    void refusesAnUnauthenticatedCaller() throws Exception {
        restMockMvc.perform(get("/api/onboarding/progress/stream")).andExpect(status().isUnauthorized());
    }

    /**
     * ⭐ <b>The push is the same computation as the {@code GET}</b>, which is what makes the
     * {@code GET} authoritative in the sense row 230 requires rather than merely first.
     *
     * <p>A consumer that applied a delta could drift from the read; this one recomputes, so the frame
     * on the socket and the body of a fresh {@code GET} are byte-identical. Asserted on a state that
     * has moved since the stream opened — the step-1 projection — so the comparison is of two live
     * answers rather than of two copies of a constant.
     */
    @Test
    void thePushedFrameIsWhatTheGetWouldAnswer() throws Exception {
        applicationRepository.save(CompleteOnboardingFixture.consentedApplication(accountIdFor(AMA), ProfileStatus.APPLICATION_STARTED));

        MvcResult stream = openStream(AMA);
        onboardingService.recordAccountCompleteness(accountIdFor(AMA), true, Instant.now());
        progressStream.push(accountIdFor(AMA), onboardingService.progressFor(accountIdFor(AMA)));

        String read = restMockMvc
            .perform(get("/api/onboarding/progress").with(gatewayUser(AMA, "ROLE_USER")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(stream.getResponse().getContentAsString()).contains(read);
    }

    /**
     * <b>{@code X-Accel-Buffering: no} is on the response, on {@code GET} and on {@code HEAD}</b> —
     * backlog.md row 230, unit B.
     *
     * <p>⚠ This summary read <i>"and without it this endpoint silently does nothing in every deployed
     * environment"</i> — <b>a claim retracted two paragraphs below, in this same comment, while
     * remaining the sentence an IDE hover and the generated docs show.</b> It was the last surviving
     * instance of the phrasing in either repository. A retracted claim left in a javadoc summary is
     * how a correction gets quietly un-made, so the summary now states what the case asserts and
     * nothing about what the header prevents.
     *
     * <h2>Why a response header and not an nginx directive</h2>
     *
     * <p>The web container's nginx — {@code deploy/docker/web-nginx.conf}, which ships in <b>both</b>
     * quality and production — proxies this path through
     * {@code location ~ ^/(api|services|management|auth|v3/api-docs|health)(/|$)} and carries <b>no
     * {@code proxy_buffering off} anywhere in the file</b>. nginx honours this header per response, so
     * the response is where it belongs. ⛔ <b>{@code /etc/nginx} and {@code deploy/} belong to the
     * architect; this header is the sanctioned route and editing nginx from here is not.</b>
     *
     * <p>⛔ <b>This case asserts the header and NOT that the header fixes anything</b>, and the
     * distinction was corrected on 2026-10-11 after this javadoc had claimed the endpoint "silently
     * does nothing" without it. Measured: nginx streams a <b>chunked</b> upstream — which is what
     * {@link SseEmitter} produces — in real time with buffering <b>on</b>. The header is defence in
     * depth. {@code OnboardingResource.progressStream} carries the full correction, including that
     * nginx <em>strips</em> this header so it can only affect the inner hop, and that
     * {@code proxy_buffering off} is quality's alone — {@code buffering} appears zero times across all
     * six {@code deploy/} configs.
     *
     * <p>⚠ <b>Asserted on the real response rather than reasoned about</b>, because the header is one
     * line, carries no behaviour of its own in a test, and is exactly the kind of thing a later
     * refactor back to a bare {@code SseEmitter} return type would drop with every other case still
     * green. Nothing in {@code api/}'s own test run can observe a proxy at all.
     */
    @Test
    void theStreamTellsNginxNotToBufferIt() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/progress/stream").with(gatewayUser(AMA, "ROLE_USER")))
            .andExpect(request().asyncStarted())
            .andExpect(header().string("X-Accel-Buffering", "no"));

        // HEAD beside GET, for the reason headIsGatedAndScopedExactlyLikeGet gives: Spring MVC
        // dispatches a HEAD to a @GetMapping handler, and a header set on one verb and not the other
        // is the shape this estate has shipped twice.
        restMockMvc
            .perform(head("/api/onboarding/progress/stream").with(gatewayUser(AMA, "ROLE_USER")))
            .andExpect(header().string("X-Accel-Buffering", "no"));
    }

    /**
     * ⭐ <b>The heartbeat is a COMMENT, and that is what stops the stream becoming a second source of
     * the number</b> — backlog.md row 230, unit B.
     *
     * <h2>Why there has to be a heartbeat at all</h2>
     *
     * <p>The same nginx location sets {@code proxy_read_timeout 120s}, sized deliberately for slow
     * {@code /services} round trips — the {@code /websocket} location's 3600s is a different location
     * and does not apply here. Unit A sends <b>no initial frame and no heartbeat</b>, both deliberately,
     * so a clinician who opens the page and changes nothing has an idle socket that nginx cuts after two
     * minutes — and ⚠ <b>the client cannot distinguish that from idleness</b>, because an SSE stream
     * that has never emitted looks identical whether it is healthy or gone.
     *
     * <p>Measured on the running quality stack before this change, as {@code nurse} through
     * {@code professional.abofonsa.local}: <b>zero bytes in eight seconds — not even the response
     * headers.</b> Nothing is flushed until something is written, so the status line itself never
     * arrived. The heartbeat is therefore not only a timeout guard; it is what makes the stream
     * <em>observable</em> at all.
     *
     * <h2>⛔ And why it must carry no data</h2>
     *
     * <p>{@code OnboardingResource.progressStream}'s javadoc protects a property: <i>"Pushing a first
     * frame here would make the stream a second source for the same number."</i> The {@code GET}
     * computes from the database and is authoritative; the stream only pushes changes. An SSE
     * <b>comment</b> — a line opening {@code :} — is discarded by every conforming client and carries
     * no event name and no payload, so it keeps the socket warm without saying anything about the
     * meter. ⛔ Do not "improve" this into a periodic progress frame: that is the second source this
     * assertion exists to refuse, and it would also make the meter's freshness depend on the broker.
     */
    @Test
    void theHeartbeatIsACommentAndSaysNothingAboutTheMeter() throws Exception {
        MvcResult stream = openStream(AMA);

        progressStream.sendHeartbeats();

        String received = stream.getResponse().getContentAsString();
        assertThat(received).as("the heartbeat must reach an open stream").isNotEmpty().startsWith(":");
        assertThat(received)
            .as("⛔ a heartbeat carries no payload — it must not become a second source of the meter")
            .doesNotContain("data:")
            .doesNotContain("event:")
            .doesNotContain(OnboardingProgressStream.EVENT_NAME)
            .doesNotContain("percent");
    }

    /**
     * A heartbeat with nobody connected is a no-op, and must not throw.
     *
     * <p>This runs on a scheduler thread every interval for the whole life of the application, and
     * almost always with no stream open at all — the ordinary case. An exception here would be logged
     * once per interval for ever, and {@code @Scheduled} suppresses it silently, so the symptom would
     * be a log nobody reads rather than a failure anybody sees.
     */
    @Test
    void aHeartbeatWithNoOpenStreamDoesNothing() {
        assertThatNoException().isThrownBy(() -> progressStream.sendHeartbeats());
    }

    private MvcResult openStream(String login) throws Exception {
        return restMockMvc
            .perform(get("/api/onboarding/progress/stream").with(gatewayUser(login, "ROLE_USER")))
            .andExpect(request().asyncStarted())
            .andReturn();
    }
}
