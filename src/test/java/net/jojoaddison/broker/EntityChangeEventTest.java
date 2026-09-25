package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import net.jojoaddison.config.ApplicationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/**
 * What one frame on {@code professional.event} carries, and — just as deliberately — what it does
 * not. Backlog.md item 141.
 *
 * <p><b>The field names are the assertion, because they are the whole of what this service and
 * hc-admin share.</b> A rename here is not a compile error on this side; it is a column that goes
 * quietly empty in somebody else's audit trail. The same reasoning is written out in
 * {@link ProfileStatusEventTest} for the other channel and applies unchanged here.
 *
 * <p><b>The absences are the other half.</b> An entity-change stream is the one wire in this
 * subsystem that fires for {@code Profile} on the same code path as everything else, so it is the one
 * place a changed value would travel without anybody choosing to send it. Two independent rules
 * forbid that — {@link DomainEventEnvelope}'s identifiers-only rule here, and hc-admin's item 110,
 * which forbids it because a channel carrying document contents rebuilds the mirror their item 107
 * exists to delete — and {@link #carriesNoFieldValues} is what makes either of them fail rather than
 * merely be written down.
 */
class EntityChangeEventTest {

    private static final Instant OCCURRED = Instant.parse("2026-09-18T09:15:00Z");

    private StreamBridge streamBridge;
    private DomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        streamBridge = mock(StreamBridge.class);
        // Same-thread, so the assertions do not race the publisher's executor. The executor's own
        // contract is asserted separately, in theQueueAbortsRatherThanRunningOnTheCallersThread.
        publisher = new DomainEventPublisher(streamBridge, new ApplicationProperties(), (Executor) Runnable::run);
    }

    @Test
    void oneChangeProducesOneFrameWithTheFiveFieldsAnAuditRowNeeds() {
        publisher.publishEntityChange("Profile", "profile-7", EntityChangeAction.UPDATED, "user-42", OCCURRED);

        EntityChangeEvent event = capture();
        assertThat(event.eventId()).isNotNull();
        // THE LITERAL, NOT ProfessionalEventType.ENTITY_CHANGED — deliberately, and for the reason
        // EntityChangeBindingTest pins the destination string rather than deriving it. This value is a
        // cross-product contract: all four .event channels carry "EntityChanged" (hc-admin item 124,
        // 2026-09-18). Asserting against the constant would pass for whatever the constant happened to
        // say, so a rename would be silent here and a dead consumer somewhere else. This service sent
        // "EntityChange" until that decision.
        assertThat(event.type()).isEqualTo("EntityChanged");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.source()).isEqualTo("hc-professional-service");

        // "when" is the envelope's occurredAt and is NOT duplicated into data. It is also the moment
        // the write happened, passed in by the announcer, rather than the moment the send ran — those
        // differ by however long the executor's queue was.
        assertThat(event.occurredAt()).isEqualTo(OCCURRED);

        // THE RECORD THAT CHANGED GOES IN subject — hc-admin item 124, decided 2026-09-18. Both keys
        // were in `data` until then, with the subject null.
        assertThat(event.subject()).isEqualTo(new EntityChangeEvent.Subject("Profile", "profile-7"));

        assertThat(event.data()).containsOnlyKeys("action", "actorAccountId");
        assertThat(event.data()).containsEntry("action", "UPDATED").containsEntry("actorAccountId", "user-42");
    }

    /**
     * ⭐ The estate's one envelope for {@code EntityChanged}, asserted as a whole.
     *
     * <p>hc-admin's item 124 found four products publishing three shapes under one {@code type},
     * because each was built from prose and read whichever siblings had landed when it looked. This
     * case is the shape as decided — {@code subject} is the record, {@code data} is what happened and
     * who did it — and it is deliberately redundant with the assertions around it, because the thing
     * that went wrong estate-wide was nobody asserting the <em>division</em> between the two
     * components.
     *
     * <p>⚠ It is still only <em>this</em> product's copy of the agreement. Item 124 says outright that
     * the decision is not done until something mechanical fails when a fifth producer diverges, and no
     * such artefact exists yet — a shared schema or a fixture all four assert against is still owed.
     */
    @Test
    void putsTheRecordInSubjectAndWhatHappenedInData() {
        publisher.publishEntityChange("Team", "team-1", EntityChangeAction.CREATED, "user-42", OCCURRED);

        EntityChangeEvent event = capture();
        assertThat(event.subject().entityType()).isEqualTo("Team");
        assertThat(event.subject().entityId()).isEqualTo("team-1");
        // ⛔ And NOT the other way round: entityType/entityId must not also linger in data, or a
        // consumer reading either place goes on working while the two silently disagree.
        assertThat(event.data()).doesNotContainKeys("entityType", "entityId");
    }

    /**
     * ⛔ The payload is identifiers and metadata only.
     *
     * <p>Asserted as {@code containsOnlyKeys} rather than as a list of things that must be absent,
     * because a list of forbidden names cannot fail when somebody adds a field nobody thought to
     * forbid — and the fields on this wire would be a clinician's, since {@code Profile} carries
     * {@code cardNumber}, {@code birthDate} and {@code address}.
     */
    @Test
    void carriesNoFieldValues() {
        publisher.publishEntityChange("Profile", "profile-7", EntityChangeAction.UPDATED, "user-42", OCCURRED);

        // The expected set shrank by two on 2026-09-18 — entityType and entityId moved to `subject`
        // (item 124) — but the assertion is unchanged in kind, and that is the point: it fails for a
        // key nobody thought to forbid, which is what a doesNotContainKey list can never do.
        assertThat(capture().data()).containsOnlyKeys("action", "actorAccountId");
        // `subject` is closed by construction rather than by assertion: it is a two-component record,
        // so a changed value cannot be added to it without a compile error.
        assertThat(capture().subject()).isEqualTo(new EntityChangeEvent.Subject("Profile", "profile-7"));
    }

    /**
     * The actor is the gateway's {@code User.id} and never a login.
     *
     * <p>This subsystem has already put a login on a wire under a name the contract called an account
     * identifier — {@code ProfileStatus.lastModifiedBy}, which hc-admin shows verbatim and joins to
     * nothing. That was tolerable for a field a screen renders and is not for one an audit trail
     * stores and item 107 D1 makes the estate's join key. The publisher takes whatever the announcer
     * resolved, so the guard that matters is on the announcer's side too
     * ({@code EntityChangeAnnouncerTest}); this case pins the field name the value arrives under.
     */
    @Test
    void theActorRidesUnderAccountIdAndNothingElse() {
        publisher.publishEntityChange("Profile", "profile-7", EntityChangeAction.UPDATED, "user-42", OCCURRED);

        assertThat(capture().data()).containsEntry("actorAccountId", "user-42").doesNotContainKeys("actor", "login", "lastModifiedBy");
    }

    /**
     * The actor is published <b>exactly as given</b> — this publisher derives no identity of its own.
     *
     * <p><b>Added by backlog.md row 220, to pin the boundary rather than the value.</b> The case above
     * pins the <em>field name</em> the actor arrives under; it cannot pin where the value came from,
     * because this class is handed one. Measured 2026-09-25: regressing {@code EntityChangeAnnouncer} to
     * {@code SecurityUtils.getCurrentUserLogin()} leaves <b>all 13 of this file's cases green</b> and
     * reddens two in {@code EntityChangeAnnouncerTest}. The provenance guard is entirely over there, and
     * this file must not be read as sharing it.
     *
     * <p>⛔ <b>What this asserts is the property that makes that division correct:</b> a login on the wire
     * could only ever be the announcer's fault, because this publisher neither resolves nor rewrites an
     * actor. Should that stop being true — if this class began deriving an identity when the argument is
     * blank, say — this case fails, and the division of labour above stops being safe to rely on.
     *
     * <p>A login-shaped value is used deliberately: it is the one input a reader might expect this class
     * to reject, and it does not. Refusing it here would put the estate's identifier rules in two places
     * and let them drift.
     */
    @Test
    void thePublisherPassesTheActorThroughAndResolvesNothingItself() {
        publisher.publishEntityChange("Profile", "profile-7", EntityChangeAction.UPDATED, "jdoe", OCCURRED);

        // Verbatim: not normalised, not replaced, not refused. Whatever the announcer resolved is what
        // ships — which is why "never a login" has to be held on the announcer's side.
        assertThat(capture().data()).containsEntry("actorAccountId", "jdoe");
    }

    /**
     * No account behind the write means no key, rather than {@code null} or {@code "system"}.
     *
     * <p>Following {@code publishProfileStatus}: a placeholder in an identifier space is a value a
     * consumer can compare against stored data and match something, where an absent one is the only
     * unambiguous "nobody". Reachable on every scheduler, migration, consumer and startup runner.
     */
    @Test
    void aWriteWithNoAccountBehindItOmitsTheActorEntirely() {
        publisher.publishEntityChange("DutyRoster", "roster-3", EntityChangeAction.CREATED, null, OCCURRED);

        EntityChangeEvent event = capture();
        assertThat(event.data()).containsOnlyKeys("action");
        assertThat(event.data()).doesNotContainKey("actorAccountId");
        // The row is still fully named: an unknown actor costs the frame its "who", never its "what".
        assertThat(event.subject()).isEqualTo(new EntityChangeEvent.Subject("DutyRoster", "roster-3"));
    }

    @Test
    void aDeleteProducesAFrameToo() {
        publisher.publishEntityChange("PersonalDocument", "doc-9", EntityChangeAction.DELETED, "user-42", OCCURRED);

        EntityChangeEvent event = capture();
        assertThat(event.data()).containsEntry("action", "DELETED");
        assertThat(event.subject().entityId()).isEqualTo("doc-9");
    }

    /**
     * Keyed on the entity, so an audit trail reads a row's frames in the order they happened.
     */
    @Test
    void isKeyedOnTheEntityIdSoOneRowsFramesStayOrdered() {
        publisher.publishEntityChange("Profile", "profile-7", EntityChangeAction.UPDATED, "user-42", OCCURRED);

        assertThat((byte[]) captureMessage().getHeaders().get(KafkaHeaders.KEY)).isEqualTo("profile-7".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * ⛔ The subject is the row, and never the actor.
     *
     * <p>This case used to assert {@code subject} was <b>null</b> — the shape this service shipped on
     * 2026-09-18 and one of three the estate's four producers published under one {@code type}. It is
     * inverted rather than deleted, because the reading it now forbids is a live one: hc-patient put
     * the actor in {@code subject}, on a genuinely good local argument, and that is the reading item
     * 124 rejected. A frame whose subject carried {@code user-42} would satisfy "the subject is
     * populated" and be wrong.
     *
     * <p>Asserted on the whole record rather than on the two components separately, so an actor
     * appearing in <em>either</em> of them fails.
     */
    @Test
    void namesTheRecordAsItsSubjectAndNeverTheActor() {
        publisher.publishEntityChange("Team", "team-1", EntityChangeAction.CREATED, "user-42", OCCURRED);

        EntityChangeEvent event = capture();
        assertThat(event.subject()).isEqualTo(new EntityChangeEvent.Subject("Team", "team-1"));
        assertThat(event.subject().entityType()).isNotEqualTo("user-42");
        assertThat(event.subject().entityId()).isNotEqualTo("user-42");
        // The actor is in data, and in exactly one place.
        assertThat(event.data()).containsEntry("actorAccountId", "user-42");
    }

    /**
     * The clinician-shaped envelope is not what this channel sends.
     *
     * <p>{@link ProfessionalEvent}'s {@code subject} is {@code (email, accountId)} and means a person;
     * it is what {@code hc.professional.registration} carries and what hc-admin consumes there today.
     * Reusing it here would have put a document's identifiers into fields called {@code email} and
     * {@code accountId} — the collision item 124 is about, recreated while fixing it. The two are
     * separate types and this pins that they stay separate on the wire.
     */
    @Test
    void doesNotSendTheClinicianShapedEnvelope() {
        publisher.publishEntityChange("Team", "team-1", EntityChangeAction.CREATED, "user-42", OCCURRED);

        Object payload = captureMessage().getPayload();
        assertThat(payload).isInstanceOf(EntityChangeEvent.class).isNotInstanceOf(ProfessionalEvent.class);
    }

    /**
     * Goes to the declared binding, not to a name StreamBridge would invent.
     */
    @Test
    void ridesTheDeclaredEntityChangeBinding() {
        publisher.publishEntityChange("Team", "team-1", EntityChangeAction.CREATED, "user-42", OCCURRED);

        verify(streamBridge).send(eq("entityChangeEvents-out-0"), any(Message.class));
    }

    /**
     * A deployment that runs no broker sends nothing, and sends it <em>before</em> reaching
     * StreamBridge — so no binding is created and {@code BindingService} never enters its retry loop.
     * That is the property {@code application.kafka.enabled=false} exists for, and suppressing the log
     * without suppressing the call would leave the loop running.
     */
    @Test
    void publishesNothingWhenTheDeploymentRunsNoBroker() {
        ApplicationProperties noBroker = new ApplicationProperties();
        noBroker.getKafka().setEnabled(false);

        new DomainEventPublisher(streamBridge, noBroker, (Executor) Runnable::run).publishEntityChange(
            "Profile",
            "profile-7",
            EntityChangeAction.UPDATED,
            "user-42",
            OCCURRED
        );

        verify(streamBridge, never()).send(any(), any(Message.class));
    }

    /**
     * ⚠ The executor aborts; it must never run the send on the caller's thread.
     *
     * <p>{@code CallerRunsPolicy} is the conventional choice for a bounded queue, it reads as
     * back-pressure, and it would hand a sixty-second first-send straight back to the request thread
     * this hop exists to protect — silently restoring the defect with every other test still green.
     * hc-admin pins the same property for the same reason.
     *
     * <p>Asserted on the real executor the Spring constructor builds, not on the test one, because the
     * test one is exactly the caller-runs behaviour being forbidden in production.
     */
    @Test
    void theQueueAbortsRatherThanRunningOnTheCallersThread() {
        Executor real = DomainEventPublisher.entityChangeExecutor();

        assertThat(real).isInstanceOf(ThreadPoolExecutor.class);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) real;
        assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(pool.getRejectedExecutionHandler()).isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        // One thread, so frames leave in the order the writes happened.
        assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
        // Bounded, so a dead broker costs a bounded amount of heap rather than all of it.
        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(DomainEventPublisher.ENTITY_CHANGE_QUEUE);
        pool.shutdownNow();
    }

    /** A rejected frame is dropped and warned about — never rethrown into the write path. */
    @Test
    void aFullQueueDropsTheFrameRatherThanFailingTheWrite() {
        Executor rejecting = runnable -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        };

        new DomainEventPublisher(streamBridge, new ApplicationProperties(), rejecting).publishEntityChange(
            "Profile",
            "profile-7",
            EntityChangeAction.UPDATED,
            "user-42",
            OCCURRED
        );

        verify(streamBridge, never()).send(any(), any(Message.class));
    }

    @SuppressWarnings("unchecked")
    private Message<EntityChangeEvent> captureMessage() {
        ArgumentCaptor<Message<EntityChangeEvent>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq(DomainEventPublisher.ENTITY_CHANGE_BINDING), captor.capture());
        return captor.getValue();
    }

    private EntityChangeEvent capture() {
        return captureMessage().getPayload();
    }
}
