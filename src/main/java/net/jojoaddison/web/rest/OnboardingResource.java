package net.jojoaddison.web.rest;

import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.OnboardingProgressStream;
import net.jojoaddison.service.OnboardingService;
import net.jojoaddison.service.dto.OnboardingProgressDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * What is left of {@code /api/onboarding} — the progress meter and the first-login
 * acknowledgement (professional-onboarding-workflow.md § Workflow step 11).
 *
 * <h2>⚠ This class used to be the whole onboarding surface, and is now three mappings</h2>
 *
 * <p>It carried twenty. {@code GET}/{@code PUT /profile} went to {@code ProfileResource} at
 * {@code /api/profile} in F8; the <b>fifteen {@code /applications*} mappings</b> went to
 * {@link ProfessionalApplicationResource} at {@code /api/professional-application} in T3, per
 * {@code profile.md} § Other Elements: <i>"{@code api/onboarding/applications} should migrate to
 * {@code api/professional-application}"</i>. {@code ComplianceResource} went with them, into the
 * same class.
 *
 * <p>⛔ <b>The three below are deliberately untouched: they are T5's.</b> {@code profile.md}
 * § Gap Update — <i>"The progress meter covers exactly these 4 steps and all their respective
 * requirements"</i> — makes {@code /progress} a refactor rather than a rename, and
 * {@code mobile/} reads it today. The acknowledgement pair is step 11's and no task has reached it.
 * Moving them here would have been a second, unreviewed decision about where they belong.
 *
 * <p>⚠ <b>The {@code .authenticated()} rule on {@code /api/onboarding/**} is still what admits
 * these three</b>, and it is still the right gate: {@code /progress} and the acknowledgement both
 * resolve the caller from the {@code uid} claim and name nobody else, and an applicant holds
 * {@code ROLE_USER} and nothing else.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingResource {

    private final OnboardingService onboardingService;

    private final OnboardingProgressStream progressStream;

    public OnboardingResource(OnboardingService onboardingService, OnboardingProgressStream progressStream) {
        this.onboardingService = onboardingService;
        this.progressStream = progressStream;
    }

    /**
     * How far the caller has got, for the meter on {@code /account/profile}.
     *
     * <p>Always answers, including for an account with no application at all — everything false,
     * 0% — because that is the state a clinician created by admin invitation starts in, and the
     * profile page has to render something for them rather than an error.
     */
    @GetMapping("/progress")
    public OnboardingProgressDTO progress() {
        return onboardingService.progressFor(currentAccountId());
    }

    /**
     * The same meter, pushed, for as long as the caller keeps the stream open (backlog.md row 230,
     * unit A, decision 3).
     *
     * <h2>⛔ The account comes from the token, and this endpoint takes no subject at all</h2>
     *
     * <p>There is no path variable, no request parameter and no header read here. That is the whole
     * of the scoping property, and it is a property of the <em>signature</em> rather than of a check:
     * the estate's rule, settled 2026-09-17, is that an endpoint which cannot name anyone but the
     * caller needs only authentication, while one that takes a subject from the path is
     * {@code ROLE_ADMIN} — and ⛔ <b>a subject-addressed endpoint must never be given a
     * self-carve-out</b>, because comparing a caller against a path is the shape that gets it wrong.
     * So: no subject.
     *
     * <p>⚠ <b>The in-tree SSE precedent is not the model.</b> {@code broker/KafkaConsumer}'s
     * {@code register(String key)} files an emitter under whatever key it is handed, and its
     * {@code accept} then broadcasts to <em>every</em> emitter in the map regardless of key. On a
     * meter stream either of those is one clinician watching another's onboarding — the shape row 226
     * closed for document bytes three hours before row 230 was written.
     * {@link OnboardingProgressStream} routes by account, and
     * {@code OnboardingProgressStreamIT} asserts two callers cannot see each other's frames.
     *
     * <p>⚠ <b>The gate is {@code /api/onboarding/**} → {@code .authenticated()}, and it is
     * verb-agnostic</b>, which matters: Spring MVC dispatches a {@code HEAD} to a {@code @GetMapping}
     * handler, and this estate has twice shipped an authority rule scoped to {@code HttpMethod.GET}
     * that a {@code HEAD} fell straight through. Nothing here would leak either way — a stream
     * resolved from the token is the caller's whatever the verb — but the case is asserted rather
     * than reasoned about, because the reasoning is what was wrong both times.
     *
     * <p><b>No initial frame is sent.</b> The client calls {@code GET /progress} for its starting
     * value: that read is authoritative, computes from the database, and answers with
     * {@code application.kafka.enabled=false} — a supported configuration. Pushing a first frame here
     * would make the stream a second source for the same number.
     *
     * <h2>{@code X-Accel-Buffering: no} — defence in depth, and <b>not</b> the repair of a measured break</h2>
     *
     * <p>Added by backlog.md row 230, <b>unit B</b>. The web container's nginx —
     * {@code deploy/docker/web-nginx.conf}, which ships in <b>both</b> quality and production — proxies
     * this path through its {@code ^/(api|services|management|auth|v3/api-docs|health)} location and
     * carries <b>no {@code proxy_buffering off} anywhere in the file</b>. nginx honours this header per
     * response, so the response is the right place to say it. ⛔ <b>Not nginx itself:</b>
     * {@code /etc/nginx} and {@code deploy/} belong to the architect, and a header the application sets
     * travels with the artefact rather than depending on another repository's file staying right.
     *
     * <h2>⛔ What this javadoc claimed, and what measurement says — corrected 2026-10-11</h2>
     *
     * <p>It asserted in bold that <i>without this header the endpoint "silently does nothing in every
     * deployed environment"</i> and that <i>"a buffered SSE response is indistinguishable from a hung
     * one"</i>. ⚠ <b>Both overstate the evidence.</b> With a <b>chunked</b> upstream — which is exactly
     * what {@link SseEmitter} produces — nginx streams SSE frames in real time with buffering
     * <b>on</b>, through one hop and through two, on 1.27.5 and on 1.31.4 — the former being what
     * {@code web.Dockerfile}'s <b>floating</b> {@code nginx:1.27-alpine} resolves to today. ⚠ Nothing
     * pins it, so that is a fact about this week rather than about the artefact. The all-at-once failure reproduces only against a non-chunked
     * {@code Connection: close} origin, which is not Spring's shape.
     *
     * <p>⛔ <b>And the "zero bytes in eight seconds" probe quoted in support of it does not distinguish
     * the hypotheses.</b> It is fully explained by the server having written nothing at all before
     * {@link OnboardingProgressStream#sendHeartbeats()} existed — which the heartbeat note below says
     * in its own words, one paragraph later. <b>A stream that has written nothing is not evidence about
     * a proxy.</b> The reading was accurate and the conclusion drawn from it did not follow, which is
     * this estate's recurring shape; the correction is kept rather than quietly edited out.
     *
     * <p>⚠ Two further facts, because getting them wrong sends a debugger to the wrong hop. nginx
     * <b>consumes and strips</b> this header, so it can only ever affect the <b>inner</b> hop and the
     * outer one never sees it. And <i>"the host vhost disables buffering"</i> is true of <b>quality
     * only</b>: {@code quality/host-site.conf} carries {@code proxy_buffering off} twice, while
     * {@code buffering} appears <b>zero times across all six nginx configs in {@code deploy/}</b> —
     * the outer production vhost included (measured on {@code origin/main}, 2026-10-11). In production
     * <b>neither</b> hop disables it.
     *
     * <p>So the header stays — one line, free, correct in intent, and the sanctioned route — but it is
     * insurance against a configuration this estate could adopt, not a fix for an observed failure.
     *
     * <p>⚠ <b>{@link ResponseEntity} is what carries it, so the return type is load-bearing</b> —
     * a refactor back to a bare {@code SseEmitter} drops the header with every other case still green.
     * {@code OnboardingProgressStreamIT.theStreamTellsNginxNotToBufferIt} asserts it on the real
     * response, on {@code HEAD} as well as {@code GET}.
     *
     * <p>⚠ <b>And an idle stream still needs a heartbeat</b>, which is the other half of the same
     * problem: the same nginx location sets {@code proxy_read_timeout 120s}. See
     * {@link OnboardingProgressStream#sendHeartbeats()}, which keeps the socket warm with a
     * <em>comment</em> — carrying no data, so the stream does not become a second source of the meter.
     */
    @GetMapping("/progress/stream")
    public ResponseEntity<SseEmitter> progressStream() {
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(progressStream.register(currentAccountId()));
    }

    public record AcknowledgementStatus(boolean acknowledged) {}

    @GetMapping("/acknowledgement")
    public AcknowledgementStatus acknowledgementStatus() {
        return new AcknowledgementStatus(onboardingService.hasAcknowledgedFirstLogin(currentAccountId()));
    }

    @PostMapping("/acknowledgement")
    public ResponseEntity<OnboardingEvent> acknowledge() {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboardingService.acknowledgeFirstLogin(currentAccountId()));
    }

    /**
     * The caller's gateway {@code User.id} — what {@code ProfessionalApplication.accountId} and
     * {@code Profile.accountId} hold and are looked up by (backlog.md item 50).
     *
     * <p>This returned {@code SecurityUtils.getCurrentUserLogin()} until that item, and the two
     * identifier spaces have been unified onto the id. A token carrying no {@code uid} claim — one
     * minted before 2026-09-07, or by hc-admin or hc-patient — resolves to nobody and is refused
     * here rather than falling back to the subject, which would key this database on a value it no
     * longer stores. Signing in again mints a token that carries the claim.
     *
     * <p>⚠ The {@code currentActor()} accessor this class also carried went to
     * {@link ProfessionalApplicationResource} with the mappings that needed it. The two were
     * deliberately separate — <b>audit fields name a person for a person to read and are never
     * looked up</b> — and they still are, over there.
     */
    private String currentAccountId() {
        return SecurityUtils.getCurrentAccountId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }
}
