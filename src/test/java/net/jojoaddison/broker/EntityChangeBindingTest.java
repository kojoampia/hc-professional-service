package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code professional.event} is a declared binding, not one StreamBridge invented.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>StreamBridge creates a <b>dynamic destination</b> for a binding name it cannot resolve. So
 * deleting {@code entityChangeEvents-out-0} from the configuration, or misspelling either side of it,
 * does not fail the build, does not fail startup and does not log an error — it opens a topic named
 * after the mistake and publishes into it. hc-admin shipped precisely that as {@code roster-events}:
 * a topic declared in no config and read by nothing, which went unnoticed because a producer writing
 * where nobody reads looks exactly like a producer working.
 *
 * <h2>It reads the file that ships, from disk, and that is the whole point</h2>
 *
 * <p>⚠ <b>{@code ClassPathResource("config/application.yml")} would resolve to
 * {@code src/test/resources/config/application.yml}</b>, because test resources precede main ones on
 * the test classpath. A test written that way asserts against a file no deployment ever loads and
 * passes while the shipped one is wrong — which is the same class of defect as the dynamic
 * destination it is guarding against, one layer up. {@link #SHIPPED} is therefore a path, and
 * {@link #theShippedConfigurationIsTheFileThisTestRead} fails if it ever stops resolving.
 *
 * <h2>The destination is asserted as a literal, deliberately</h2>
 *
 * <p>Deriving it — "the binding has <em>a</em> destination" — would pass on a typo. A cross-product
 * topic name is the one case where that matters most: a misspelling here is a real topic on this side
 * and a real subscription on hc-admin's, both healthy, both silent, with the group at lag zero. The
 * name was fixed by the estate on 2026-09-17 and changing it is a coordinated deploy across two
 * products, so a literal costing one edit on the day it legitimately changes is the right trade.
 * hc-admin pins {@code patient-events-plan} the same way and records the same reasoning.
 */
class EntityChangeBindingTest {

    private static final Path SHIPPED = Path.of("src/main/resources/config/application.yml");

    /**
     * The estate's name, decided 2026-09-17: one channel per product carrying every entity CRUD
     * event. <b>Not {@code hc.professional.entity} renamed</b> — that topic keeps running and hc-admin
     * keeps consuming it; retiring either of the older two is backlog.md item 142.
     */
    private static final String TOPIC = "professional.event";

    @Test
    void theShippedConfigurationIsTheFileThisTestRead() {
        assertThat(SHIPPED).as("the shipped application.yml moved; this test is asserting against nothing").exists();
    }

    @Test
    void theEntityChangeBindingIsDeclaredAndPointsAtTheEstatesTopic() {
        Map<String, Object> binding = bindings().get(DomainEventPublisher.ENTITY_CHANGE_BINDING);

        assertThat(binding)
            .as("%s is not declared, so StreamBridge would open a dynamic destination instead", DomainEventPublisher.ENTITY_CHANGE_BINDING)
            .isNotNull();
        assertThat(binding).containsEntry("destination", TOPIC).containsEntry("content-type", "application/json");
    }

    /**
     * ⛔ The two live topics are untouched.
     *
     * <p>hc-admin consumes {@code hc.professional.entity} through
     * {@code professionalProfileConsumer-in-0} right now, and this subsystem's own
     * {@code messageEvents-in-0} and {@code pushEvents-in-0} read it too. Item 141 adds a channel; it
     * removes nothing. This case is here because "add the new one" and "repoint the old one" are a
     * one-line difference in this file, and the second breaks three consumers silently.
     */
    @Test
    void theExistingChannelsAreUnchanged() {
        Map<String, Map<String, Object>> bindings = bindings();

        assertThat(bindings.get(DomainEventPublisher.ENTITY_TOPIC_BINDING)).containsEntry("destination", "hc.professional.entity");
        assertThat(bindings.get(DomainEventPublisher.ONBOARDING_STATE_BINDING)).containsEntry(
            "destination",
            "hc.professional.registration"
        );
        assertThat(bindings.get("messageEvents-in-0")).containsEntry("destination", "hc.professional.entity");
        assertThat(bindings.get("pushEvents-in-0")).containsEntry("destination", "hc.professional.entity");
    }

    /**
     * Each channel is its own destination. Publishing entity changes onto either live topic would
     * reach consumers built for a different contract — hc-admin's {@code SiblingEventParser} accepts
     * only {@code ProfileStatus} on the entity topic, so the frames would be refused in silence.
     */
    @Test
    void theNewChannelDoesNotShareATopicWithTheOldOnes() {
        Map<String, Map<String, Object>> bindings = bindings();

        assertThat(bindings.get(DomainEventPublisher.ENTITY_CHANGE_BINDING).get("destination"))
            .isNotEqualTo(bindings.get(DomainEventPublisher.ENTITY_TOPIC_BINDING).get("destination"))
            .isNotEqualTo(bindings.get(DomainEventPublisher.ONBOARDING_STATE_BINDING).get("destination"));
    }

    /**
     * The bindings block of the shipped file.
     *
     * <p>{@code loadAll} rather than {@code load}: this is a multi-document YAML — Spring separates
     * its profile-specific sections with {@code ---}, and SnakeYAML's single-document {@code load}
     * throws "expected a single document in the stream" on it rather than returning the first. Each
     * document is searched and the first declaring the block wins, which is also what Spring does when
     * no later profile overrides it.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> bindings() {
        try (InputStream in = Files.newInputStream(SHIPPED)) {
            for (Object document : new Yaml().loadAll(in)) {
                Object bindings = descend(document, "spring", "cloud", "stream", "bindings");
                if (bindings instanceof Map) {
                    return (Map<String, Map<String, Object>>) bindings;
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not read the shipped configuration at " + SHIPPED.toAbsolutePath(), e);
        }
        throw new AssertionError("no spring.cloud.stream.bindings block in " + SHIPPED.toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private Object descend(Object node, String... keys) {
        for (String key : keys) {
            if (!(node instanceof Map)) {
                return null;
            }
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }
}
