package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import net.jojoaddison.config.AsyncSyncConfiguration;
import net.jojoaddison.config.EmbeddedKafka;
import net.jojoaddison.config.EmbeddedMongo;
import net.jojoaddison.config.JacksonConfiguration;
import net.jojoaddison.config.KafkaTestContainer;
import net.jojoaddison.config.TestJacksonConfiguration;
import net.jojoaddison.domain.AccountCompleteness;
import net.jojoaddison.repository.AccountCompletenessRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * ⛔ <b>A real {@code AccountDetailsUpdated} frame, on a real broker, reaches
 * {@code MeterConsumer} and lands in {@code account_completeness}</b> — the half of row 230's
 * consumer that no other test in this repository could reach.
 *
 * <h2>Why this class has to exist, and why {@code MeterBindingTest} is not enough</h2>
 *
 * <p>{@code MeterBindingTest} compares two strings in two files. It cannot see whether a frame
 * arrives, and <b>nothing else in the repository could</b>: the test classpath's
 * {@code src/test/resources/config/application.yml:45} declares
 * {@code definition: kafkaConsumer;kafkaProducer} and <em>replaces</em> the shipped file rather than
 * layering over it, so under {@code @IntegrationTest} <b>neither meter consumer is bound at all</b>.
 * The entire consumer half of this package was unexercised.
 *
 * <p>⚠ That is the shape that cost this estate a dead production service — backlog.md items 50 and
 * 86, where a production-only configuration error passed 453 green integration tests because the
 * test config replaced the file carrying the mistake. {@code ApplicationPropertiesBindIT} closes it
 * for the {@code application.*} prefix by reading the shipping YAML; this closes it for the one
 * {@code spring.cloud.function} key the meter depends on, by <b>binding the consumer and sending it
 * something</b>.
 *
 * <p>⚠ <b>{@code DomainEventsKafkaIT} could not host these cases.</b> It is an
 * {@code @IntegrationTest}, so it inherits that same test config and has no bound consumer — it
 * asserts on records it reads back off the topic itself, which is a different and weaker claim.
 * Hence a context of its own, overriding the function definition and the two bindings the shipped
 * file declares, exactly as {@code OnboardingProgressWithoutBrokerIT} overrides
 * {@code application.kafka.enabled}.
 *
 * <h2>⭐ The bean names are load-bearing here, which is what makes this a guard and not a demo</h2>
 *
 * <p>The {@code properties} below name the functions as literals, because an annotation attribute
 * must be a compile-time constant. <b>That is the point rather than a limitation:</b> rename a
 * {@code @Bean} in {@code MeterStreamConfiguration} and this definition resolves to nothing, no
 * binding is created, no frame is consumed and {@link #aRealAccountDetailsFrameReachesTheProjection}
 * fails. Measured: with {@code @Bean("meterAccountEventsTYPO")} this class fails while
 * {@code MeterBindingTest} stays green — which is the pair of facts that justifies keeping both.
 *
 * <p>The group is this class's own, so it competes with nothing on a shared broker, and
 * {@code startOffset=earliest} with a re-send loop removes the rebalance race: a frame produced
 * before the consumer group has finished joining would otherwise be the one thing that made this
 * flaky, and re-sending an identical frame is harmless because the event is an idempotent snapshot
 * and {@code MeterConsumer} dedupes on {@code eventId} <em>after</em> consumption.
 */
@SpringBootTest(
    classes = {
        net.jojoaddison.ProfessionalServiceApp.class,
        JacksonConfiguration.class,
        TestJacksonConfiguration.class,
        AsyncSyncConfiguration.class,
    },
    properties = {
        // THE WHOLE REASON THIS CLASS EXISTS. The test classpath's application.yml says
        // "kafkaConsumer;kafkaProducer"; the shipped one names the meter consumers. Without this
        // override there is no bound consumer and the case below would pass vacuously by timing out
        // on a projection nothing was ever going to write.
        "spring.cloud.function.definition=meterAccountEvents;meterEntityEvents",
        "spring.cloud.stream.bindings.meterAccountEvents-in-0.destination=hc.professional.registration",
        "spring.cloud.stream.bindings.meterAccountEvents-in-0.content-type=application/json",
        "spring.cloud.stream.bindings.meterAccountEvents-in-0.group=hc-professional-ms-meter-it",
        "spring.cloud.stream.bindings.meterEntityEvents-in-0.destination=professional.event",
        "spring.cloud.stream.bindings.meterEntityEvents-in-0.content-type=application/json",
        "spring.cloud.stream.bindings.meterEntityEvents-in-0.group=hc-professional-ms-meter-changes-it",
        "spring.cloud.stream.kafka.bindings.meterAccountEvents-in-0.consumer.startOffset=earliest",
        "spring.cloud.stream.kafka.bindings.meterEntityEvents-in-0.consumer.startOffset=earliest",
    }
)
@EmbeddedMongo
@EmbeddedKafka
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MeterConsumerBindingIT {

    private static final String REGISTRATION_TOPIC = "hc.professional.registration";

    /** Unique per run, so a reused broker's retained records cannot satisfy this class's assertions. */
    private static final String ACCOUNT_ID = "uid-binding-it-" + UUID.randomUUID();

    @Autowired
    private AccountCompletenessRepository accountCompletenessRepository;

    @Autowired
    private KafkaTestContainer kafkaTestContainer;

    @BeforeEach
    void setUp() {
        accountCompletenessRepository.deleteAll();
    }

    @AfterEach
    void cleanup() {
        accountCompletenessRepository.deleteAll();
    }

    /**
     * ⛔ The end-to-end claim: the gateway's JSON, as the gateway actually serialises it, becomes a
     * row.
     *
     * <p>The frame is written as a <b>string literal</b> rather than built from this repository's own
     * record, deliberately. The producer is another application; serialising
     * {@code AccountDetailsEvent} here and deserialising it back would prove only that this record
     * round-trips with itself, which is true of any record and is not the contract. What is asserted
     * is that the bytes {@code gateway/}'s {@code ProfessionalEvent} produces — seven fields, with
     * {@code subject} carrying an {@code email} this service does not read — are understood.
     *
     * <p>⚠ {@code @Timeout} is explicit because {@code junit-platform.properties} sets a 15-second
     * default for every test method, and a broker round trip on a cold binder exceeds it. Without
     * this the failure would read as an unexplained timeout rather than as a missing row.
     */
    @Test
    @Timeout(120)
    void aRealAccountDetailsFrameReachesTheProjection() throws Exception {
        Instant occurredAt = Instant.parse("2026-10-10T09:00:00Z");

        AccountCompleteness stored = sendUntilProjected(frame(UUID.randomUUID().toString(), true, occurredAt));

        assertThat(stored).as("no account_completeness row — the consumer is not bound, or the frame was not understood").isNotNull();
        assertThat(stored.isComplete()).isTrue();
        assertThat(stored.getObservedAt()).as("observedAt must be the event's occurredAt, not the receive time").isEqualTo(occurredAt);
        assertThat(stored.getAccountId()).isEqualTo(ACCOUNT_ID);
    }

    /**
     * ⭐ <b>The other envelope shape on this topic is tolerated, measured rather than reasoned.</b>
     *
     * <p>{@code hc.professional.registration} carries {@code registration.created} and
     * {@code onboarding.state} on {@code DomainEventEnvelope}'s {@code eventType}/{@code actor}/
     * {@code payload} shape, and this binding is typed to {@link AccountDetailsEvent}. The risk was a
     * <em>conversion</em> failure before any dispatch ran — which does not ignore the frame, it goes
     * to the binder's error path and retries, once per unrelated frame on a topic this service's own
     * publisher writes to.
     *
     * <p>{@code AccountDetailsEvent} carries {@code @JsonIgnoreProperties(ignoreUnknown = true)} for
     * exactly that, and <b>this case is what turned that from an argument into a measurement</b>: a
     * foreign-shaped frame is sent on the same key — therefore the same partition, therefore strictly
     * before — and the real frame that follows is still applied. A conversion failure that blocked or
     * poisoned the partition would show up as the row never arriving.
     */
    @Test
    @Timeout(120)
    void aForeignEnvelopeOnTheSameTopicDoesNotBlockTheRealOne() throws Exception {
        String registrationCreated =
            """
            {"eventId":"%s","eventType":"registration.created","occurredAt":"2026-10-10T08:59:00Z",
             "source":"hc-professional-gateway","actor":"ama.serwaa",
             "payload":{"accountId":"%s","login":"ama.serwaa","email":"ama@localhost","langKey":"en","origin":"self-service"}}
            """.formatted(UUID.randomUUID().toString(), ACCOUNT_ID);

        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>(REGISTRATION_TOPIC, ACCOUNT_ID, registrationCreated)).get();
        }

        AccountCompleteness stored = sendUntilProjected(frame(UUID.randomUUID().toString(), true, Instant.parse("2026-10-10T09:00:00Z")));

        assertThat(stored)
            .as("the foreign-shaped frame ahead of it blocked the partition — see AccountDetailsEvent's @JsonIgnoreProperties")
            .isNotNull();
        assertThat(stored.isComplete()).isTrue();
    }

    /**
     * ⛔ <b>The ordering guard holds across the broker</b>, not only in a unit test.
     *
     * <p>A {@code false} at a later {@code occurredAt} is applied; a {@code true} at an earlier one is
     * refused. At-least-once delivery is not at-least-once ordering, so a redelivered older frame
     * really can arrive after a newer one, and applying it would move step 1 backwards and leave it
     * there until the account was next written.
     *
     * <p>Sent on one key so the partition — and therefore the arrival order — is fixed, which is what
     * makes "the later one wins" a statement about the guard rather than about the broker.
     */
    @Test
    @Timeout(120)
    void anOlderFrameArrivingLaterDoesNotMoveStepOneBackwards() throws Exception {
        Instant newer = Instant.parse("2026-10-10T10:00:00Z");
        Instant older = Instant.parse("2026-10-10T09:00:00Z");

        AccountCompleteness afterNewer = sendUntilProjected(
            frame(UUID.randomUUID().toString(), false, newer),
            stored -> newer.equals(stored.getObservedAt())
        );
        assertThat(afterNewer).isNotNull();
        assertThat(afterNewer.getObservedAt()).isEqualTo(newer);

        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>(REGISTRATION_TOPIC, ACCOUNT_ID, frame(UUID.randomUUID().toString(), true, older))).get();
        }

        // Nothing to wait FOR — the assertion is that nothing changes — so the window is bounded by a
        // second frame whose effect IS observable: once the marker below has landed, the stale frame
        // ahead of it on the same partition has certainly been handled.
        AccountCompleteness afterMarker = sendUntilProjected(
            frame(UUID.randomUUID().toString(), false, newer.plusSeconds(1)),
            stored -> newer.plusSeconds(1).equals(stored.getObservedAt())
        );

        assertThat(afterMarker).isNotNull();
        assertThat(afterMarker.isComplete()).as("a stale true overwrote a newer false").isFalse();
        assertThat(afterMarker.getObservedAt()).isEqualTo(newer.plusSeconds(1));
    }

    /**
     * {@code gateway/}'s {@code ProfessionalEvent} on the wire, field for field — including the
     * {@code subject.email} this service never reads and the {@code version} it does not act on.
     */
    private String frame(String eventId, boolean detailsComplete, Instant occurredAt) {
        return """
        {"eventId":"%s","type":"AccountDetailsUpdated","version":1,"occurredAt":"%s",
         "source":"hc-professional-gateway","subject":{"email":"ama@localhost","accountId":"%s"},
         "data":{"detailsComplete":%s}}
        """.formatted(eventId, occurredAt.toString(), ACCOUNT_ID, detailsComplete);
    }

    /**
     * Sends the frame until the projection reflects it, or the deadline passes.
     *
     * <p><b>A re-send loop rather than a send-once-then-poll</b>, because the consumer group may not
     * have finished joining when the first record is produced — the one thing that would have made
     * this class flaky. Re-sending an identical frame is safe: the event is an idempotent snapshot,
     * and {@code MeterConsumer}'s dedupe is an in-memory set populated <em>on consumption</em>, so a
     * frame that was never consumed is not deduped away.
     *
     * <p>The deadline is 40 seconds per case. Generous against the ~15 seconds a cold binder
     * actually takes here, and deliberately not more: the whole class costs 47 s green, and a
     * genuinely broken binding has to fail in a readable amount of time rather than three times the
     * deadline. Measured with a renamed bean, the three cases fail by assertion in ~150 s.
     *
     * @return the row, or {@code null} — the caller asserts, so a failure names the missing row
     *     rather than throwing out of a helper.
     */
    private AccountCompleteness sendUntilProjected(String frame) throws Exception {
        return sendUntilProjected(frame, stored -> true);
    }

    /**
     * ⚠ <b>The predicate overload is not a convenience.</b> A row exists after the first case's
     * frame, so "wait until a row is present" returns immediately for every later send and would
     * assert about the PREVIOUS frame's effect — a test that passes without the frame under test
     * having been handled at all. Every send after the first must wait for a condition its own frame
     * is what satisfies.
     */
    private AccountCompleteness sendUntilProjected(String frame, java.util.function.Predicate<AccountCompleteness> settled)
        throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(40).toMillis();
        try (KafkaProducer<String, String> producer = producer()) {
            while (System.currentTimeMillis() < deadline) {
                producer.send(new ProducerRecord<>(REGISTRATION_TOPIC, ACCOUNT_ID, frame)).get();
                for (int attempt = 0; attempt < 10; attempt++) {
                    AccountCompleteness stored = accountCompletenessRepository.findById(ACCOUNT_ID).orElse(null);
                    if (stored != null && settled.test(stored)) {
                        return stored;
                    }
                    Thread.sleep(500);
                }
            }
        }
        return null;
    }

    private KafkaProducer<String, String> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaTestContainer.getKafkaContainer().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }
}
