package net.jojoaddison.service;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.jojoaddison.service.dto.OnboardingProgressDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live onboarding meters, one stream per <b>account</b>, pushed when that account's own state changes
 * (backlog.md row 230, unit A).
 *
 * <h2>⛔ An emitter is filed under an account id, and the account id comes from the token</h2>
 *
 * <p>The in-tree precedent is {@code broker/KafkaConsumer}, and <b>this class deliberately does not
 * copy it</b> on two counts, both of which would be a cross-account read on a meter stream:
 *
 * <ul>
 *   <li><b>Its {@code register(String key)} takes whatever key it is handed.</b> Its one caller today
 *       passes {@code principal.getName()}, so nothing leaks — but the signature is an invitation,
 *       and a second caller passing a request parameter would be a clinician subscribing to another
 *       clinician's progress. {@link #register(String)} here is reached only from
 *       {@code OnboardingResource}, which resolves the account from {@code getCurrentAccountId()} and
 *       accepts <b>no</b> parameter, header or path segment that could name anybody else.</li>
 *   <li>⭐ <b>Its {@code accept} broadcasts to every emitter in the map regardless of key</b> — the
 *       key is used for registration and removal and never for routing. So on that class the scoping
 *       question is not merely weakly enforced, it is not asked: one event reaches every connected
 *       client. {@link #push} sends to one account's emitters and to nothing else, and
 *       {@code OnboardingProgressStreamIT} asserts that two accounts do not see each other's frames.</li>
 * </ul>
 *
 * <p>This repository closed row 226 — an unowned read of another clinician's identity-document bytes,
 * measured on the running quality stack — three hours before row 230 was written. The same rule
 * applies here and the estate's version of it is in the workspace guide: <i>an endpoint that cannot
 * name anyone but the caller needs only authentication; an endpoint that takes a subject from the
 * path is {@code ROLE_ADMIN}</i>. A meter stream has no business taking a subject at all, so it takes
 * none — ⛔ <b>do not add a parameter to this endpoint and then compare it against the caller</b>;
 * that is the shape that gets it wrong.
 *
 * <h2>Where this class lives, and why not in {@code broker}</h2>
 *
 * <p>{@code TechnicalStructureTest} allows {@code ..service..} to be reached from {@code ..web..} and
 * {@code ..config..} only, so a {@code @Component} in {@code ..broker..} may not reach a service —
 * which is why {@code PushNotificationConfiguration} declares its consumer function as a Config bean.
 * The registry has to be reachable from the consumer side <em>and</em> from the resource, so it sits
 * in Service where both may legally reach it. {@link SseEmitter} is Spring's, not this
 * application's {@code ..web..}, so holding one here breaks no layer.
 *
 * <h2>Several emitters per account, and a failed send is a dead client</h2>
 *
 * <p>One clinician can have the portal open in two tabs and the mobile app besides, so an account maps
 * to a <em>set</em>. {@code KafkaConsumer}'s {@code Map<String, SseEmitter>} silently drops the first
 * registration when a second arrives under the same key, which on a meter would leave one tab live and
 * the other permanently silent.
 *
 * <p>A send that throws means that client is gone — the socket is closed and the container has not yet
 * run the completion callback. The emitter is completed and dropped rather than retried: there is
 * nothing to retry to, and {@link #push} rides a Kafka consumer thread, so a retry loop here would
 * stall the partition. ⚠ <b>Nothing here propagates</b>, for the same reason the publishers catch
 * their own failures: a consumer that throws costs the whole binding, and a meter is a courtesy.
 */
@Service
public class OnboardingProgressStream {

    private static final Logger log = LoggerFactory.getLogger(OnboardingProgressStream.class);

    /** The SSE event name clients subscribe to. Named so a second kind of frame can be added later. */
    public static final String EVENT_NAME = "onboarding-progress";

    /**
     * How often {@link #sendHeartbeats} runs, in milliseconds.
     *
     * <p><b>30s against nginx's {@code proxy_read_timeout 120s}</b> — a four-fold margin, so <b>two</b>
     * consecutive heartbeats can be lost and the next write still arrives inside the window. ⚠ This
     * said <i>three</i>; at 30s the write after three losses lands at exactly t=120s, on the boundary. ⚠ It
     * must stay comfortably under that 120s, which lives in {@code deploy/docker/web-nginx.conf} and
     * therefore in another repository: see {@link #sendHeartbeats} for why this is a constant and not a
     * bound property.
     */
    public static final long HEARTBEAT_MILLIS = 30_000L;

    /**
     * The body of the heartbeat comment.
     *
     * <p>Not empty: {@code SseEmitter} writes a comment as {@code ":" + text + "\n\n"}, and a nameless
     * one byte on the wire is harder to recognise in a packet capture or an nginx debug log than a word
     * is. ⛔ It must stay a comment and must not become data — {@link #sendHeartbeats} says why.
     */
    private static final String HEARTBEAT_COMMENT = "heartbeat";

    /**
     * Emitters by account id. ⚠ <b>The key is never client-supplied</b> — see the class comment.
     *
     * <p>{@code ConcurrentHashMap} with a concurrent set per account because registration runs on a
     * request thread and {@link #push} runs on a Kafka consumer thread, so the two races are real
     * rather than theoretical.
     */
    private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Opens a stream for one account.
     *
     * @param accountId the caller's own account id, resolved from the {@code uid} claim by the
     *     resource. ⛔ Never a value a request carried.
     */
    public SseEmitter register(String accountId) {
        // No timeout: the browser reconnects on a closed stream, so a container-default timeout would
        // reconnect every clinician every thirty seconds for no benefit. nginx's read timeout is what
        // bounds this in the deployed stack.
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.computeIfAbsent(accountId, key -> ConcurrentHashMap.newKeySet()).add(emitter);
        // All three, not only onCompletion: a client that disappears without closing cleanly raises
        // onTimeout or onError instead, and an emitter left in the map would be sent to for ever.
        emitter.onCompletion(() -> remove(accountId, emitter));
        emitter.onTimeout(() -> remove(accountId, emitter));
        emitter.onError(throwable -> remove(accountId, emitter));
        log.debug("Opened an onboarding progress stream for {}", accountId);
        return emitter;
    }

    /**
     * Pushes a meter to one account's own streams, and to nothing else.
     *
     * <p>Silent when that account has no stream open, which is the ordinary case: an event arrives
     * for every applicant whether or not they are looking at the page.
     */
    public void push(String accountId, OnboardingProgressDTO progress) {
        Collection<SseEmitter> open = emitters.get(accountId);
        if (open == null || open.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(open)) {
            try {
                emitter.send(SseEmitter.event().name(EVENT_NAME).data(progress));
            } catch (IOException | IllegalStateException e) {
                // The client is gone. Completing it runs the callback above, which removes it.
                log.debug("Dropping a closed onboarding progress stream for {}", accountId);
                remove(accountId, emitter);
                emitter.complete();
            }
        }
    }

    /**
     * Keeps every open stream warm with a <b>comment</b>, so nginx does not cut an idle one
     * (backlog.md row 230, unit B).
     *
     * <h2>⚠ Why an idle stream dies, and why the client cannot tell</h2>
     *
     * <p>{@code deploy/docker/web-nginx.conf} — the web container's nginx, which ships in <b>both</b>
     * quality and production — proxies this path through its
     * {@code ^/(api|services|management|auth|v3/api-docs|health)} location and sets
     * {@code proxy_read_timeout 120s}, sized for slow {@code /services} round trips. ({@code /websocket}
     * has 3600s and is a <em>different</em> location; it does not apply here.) {@link #register} opens
     * the emitter with no timeout of its own precisely because nginx is what bounds it — so with no
     * traffic at all, a clinician who opens the page and changes nothing has their socket closed after
     * two minutes.
     *
     * <p>⛔ <b>And an SSE stream that has never emitted is indistinguishable from a dead one.</b>
     * Measured on the running quality stack before this method existed, as {@code nurse} through
     * {@code professional.abofonsa.local}: <b>zero bytes in eight seconds, not even the response
     * headers</b> — nothing is flushed until something is written, so even the status line never
     * arrives. So this is not only a timeout guard; it is what makes the stream observable.
     *
     * <p>⚠ <b>That probe says nothing about buffering, and it was briefly cited as though it did.</b>
     * The response is empty because <em>this application wrote nothing</em> — which is the sentence
     * immediately above. A stream that has written nothing cannot be evidence about a proxy.
     * {@code OnboardingResource.progressStream} carries the correction in full. The heartbeat is why
     * the same probe would answer differently today; nginx has not changed.
     *
     * <h2>⭐ A comment, which is the whole point</h2>
     *
     * <p>{@code OnboardingResource.progressStream} protects a property: the {@code GET} computes from
     * the database and is authoritative, and <i>"pushing a first frame here would make the stream a
     * second source for the same number"</i>. An SSE comment — a line opening {@code :} — is discarded
     * by every conforming client and carries no event name and no payload, so it keeps the socket warm
     * <b>without saying anything about the meter</b>. ⛔ Do not turn this into a periodic progress
     * frame: that is the second source, and it would make the meter's freshness depend on the broker,
     * which row 230 argued against at length.
     *
     * <p>⚠ <b>{@link #HEARTBEAT_MILLIS} is a constant rather than a bound property, deliberately.</b>
     * The value that matters is nginx's timeout, which ships from a different repository, so a
     * deploy-time knob here could be set <em>above</em> it and reintroduce exactly this defect with
     * nothing failing. {@code api/}'s {@code ApplicationProperties} is also
     * {@code ignoreUnknownFields=false} and its tests load a test-resources config that replaces the
     * production one, so a new {@code application.*} key is the one class of change {@code clean
     * verify} cannot see (backlog.md rows 50 and 86). A constant costs an image rebuild to change and
     * cannot fail a production context.
     */
    @Scheduled(fixedRateString = "#{T(net.jojoaddison.service.OnboardingProgressStream).HEARTBEAT_MILLIS}")
    public void sendHeartbeats() {
        for (Map.Entry<String, Set<SseEmitter>> account : emitters.entrySet()) {
            for (SseEmitter emitter : List.copyOf(account.getValue())) {
                try {
                    emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
                } catch (IOException | IllegalStateException e) {
                    // Same reading as in push: a send that throws means the client is gone. A
                    // heartbeat is in fact the only thing that notices a client which disappeared
                    // without closing cleanly while nothing was being pushed.
                    log.debug("Dropping a closed onboarding progress stream for {}", account.getKey());
                    remove(account.getKey(), emitter);
                    emitter.complete();
                }
            }
        }
    }

    /**
     * How many streams this account has open.
     *
     * <p>Two callers and no more: {@code MeterConsumer} skips the whole resolve-and-recompute when
     * nobody is connected, and {@code OnboardingProgressStreamIT} asserts that a request opened the
     * caller's own stream and nobody else's. ⛔ Not a surface for a client — nothing in
     * {@code web.rest} may answer how many sockets an account holds.
     */
    public int openStreams(String accountId) {
        Set<SseEmitter> open = emitters.get(accountId);
        return open == null ? 0 : open.size();
    }

    private void remove(String accountId, SseEmitter emitter) {
        emitters.computeIfPresent(accountId, (key, open) -> {
            open.remove(emitter);
            // The empty set is unmapped rather than left behind: this map is keyed by account and
            // would otherwise grow by one entry per clinician who ever opened the page.
            return open.isEmpty() ? null : open;
        });
    }
}
