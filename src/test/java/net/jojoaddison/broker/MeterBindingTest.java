package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.jojoaddison.config.MeterStreamConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.Yaml;

/**
 * The live completion meter's two consumer bindings are declared, point at the right topics, and are
 * <b>named in {@code spring.cloud.function.definition}</b> — backlog.md row 230, unit A.
 *
 * <h2>⛔ Why the function definition is the assertion that matters most</h2>
 *
 * <p>{@code EntityChangeBindingTest} beside this one guards a <em>producer</em> binding, where the
 * failure is StreamBridge opening a dynamic destination named after a typo. A <em>consumer</em> has a
 * different and quieter failure: <b>a {@code Consumer} bean that is not named in
 * {@code spring.cloud.function.definition} is never bound at all.</b> It is a bean, it is injectable,
 * its unit tests pass, and no frame ever reaches it.
 *
 * <p>This repository already ships one instance of exactly that — the generated {@code kafkaConsumer},
 * dormant since the scaffold with a binding declared beside it and its own comment saying so. Against
 * a meter, dormancy looks like <i>"the page does not refresh by itself"</i>, which nobody files.
 *
 * <h2>It reads the file that ships, from disk</h2>
 *
 * <p>⚠ {@code ClassPathResource("config/application.yml")} would resolve to
 * {@code src/test/resources/config/application.yml}, because test resources precede main ones on the
 * test classpath — and that file carries a <em>different</em> function definition on purpose. A test
 * written that way would assert against a file no deployment loads. {@link #SHIPPED} is a path, and
 * {@link #theShippedConfigurationIsTheFileThisTestRead} fails if it ever stops resolving. The
 * reasoning is {@code EntityChangeBindingTest}'s and is spelled out there at length.
 */
class MeterBindingTest {

    private static final Path SHIPPED = Path.of("src/main/resources/config/application.yml");

    /** The gateway's step-1 verdict rides the clinician topic — see {@code AccountDetailsEvent}. */
    private static final String REGISTRATION_TOPIC = "hc.professional.registration";

    /** Steps 2–4 ride the estate's per-product entity channel, which this service already publishes to. */
    private static final String ENTITY_CHANGE_TOPIC = "professional.event";

    private static final String ACCOUNT_BINDING = "meterAccountEvents-in-0";
    private static final String ENTITY_BINDING = "meterEntityEvents-in-0";

    @Test
    void theShippedConfigurationIsTheFileThisTestRead() {
        assertThat(SHIPPED).as("the shipped application.yml moved; this test is asserting against nothing").exists();
    }

    /**
     * ⛔ <b>Every {@code Consumer} bean {@code MeterStreamConfiguration} declares is named in the
     * function definition</b> — and the bean names are <b>read off the beans</b>, not spelled here.
     *
     * <h2>⚠ This assertion was two literals that happened to agree, and it did not guard the thing
     * this class exists for</h2>
     *
     * <p>It asserted that the shipped {@code definition} contained the strings
     * {@code "meterAccountEvents"} and {@code "meterEntityEvents"} — written in this file. <b>Rename
     * a {@code @Bean} and the YAML still contains those strings: green test, dormant consumer,
     * context starts normally, no frames arrive.</b> Proved by mutation: with
     * {@code @Bean("meterAccountEventsTYPO")} this class went 6/6 green and 45 other tests passed
     * beside it.
     *
     * <p>That is this repository's own documented trap landing inside the guard written against it —
     * the dormant generated {@code kafkaConsumer} is cited as this class's whole reason for existing,
     * and <em>a check whose reach depends on prose is not a check</em>. The javadoc above stated the
     * hazard correctly and the assertion below it did not cover it.
     *
     * <p><b>So the two sides are now asserted against EACH OTHER.</b> The names come from the
     * {@code @Bean} annotations by reflection, so a rename on either side fails: rename the bean and
     * its derived name is absent from the YAML; rename the YAML entry and the bean's name is absent
     * from the definition. A coordinated rename of both is a legitimate change and passes, which is
     * correct. The shape is {@code AuthoritiesConstantsUnitTest}'s — reflect rather than enumerate,
     * so a third consumer added later is covered without anyone editing this test.
     *
     * <p>⚠ <b>This still only proves two strings agree, not that a frame arrives.</b>
     * {@link MeterConsumerBindingIT} is the half that binds the consumer for real, and neither
     * replaces the other: the test classpath's {@code application.yml} <em>replaces</em> the shipped
     * one and declares {@code definition: kafkaConsumer;kafkaProducer}, so no ordinary integration
     * test in this repository binds either meter consumer.
     */
    @Test
    void everyMeterConsumerBeanIsNamedInTheFunctionDefinition() {
        List<String> declared = Arrays.stream(String.valueOf(functionDefinition()).split(";")).map(String::trim).toList();
        List<String> beanNames = meterConsumerBeanNames();

        assertThat(beanNames).as("MeterStreamConfiguration declares no Consumer bean at all; this test is asserting nothing").isNotEmpty();
        assertThat(declared)
            .as(
                "a Consumer bean missing from spring.cloud.function.definition is never bound and nothing says so — " +
                "MeterStreamConfiguration declares %s, the shipped definition names %s",
                beanNames,
                declared
            )
            .containsAll(beanNames);
        // The two that were already there, so adding the meter's did not displace them: this property
        // is one delimited string, and overwriting it is a one-character mistake that would silently
        // stop the websocket nudges and the push notifications. Literals on purpose — those beans are
        // declared elsewhere and this case is about not clobbering them.
        assertThat(declared).contains("messageEvents", "pushEvents");
    }

    /**
     * The {@code Consumer} bean names {@code MeterStreamConfiguration} declares, read off the
     * {@code @Bean} annotations rather than written down.
     *
     * <p>Spring's own resolution order is honoured: an explicit {@code @Bean} value wins and an empty
     * one falls back to the method name. Both, rather than assuming the explicit form — dropping the
     * annotation's value is exactly the kind of edit this test has to survive.
     *
     * <p>Filtered on the return <em>type</em> rather than on a name pattern: a bean that is not a
     * {@code Consumer} is not a stream binding and has no business in the function definition.
     */
    private List<String> meterConsumerBeanNames() {
        return Arrays.stream(MeterStreamConfiguration.class.getDeclaredMethods())
            .filter(method -> Consumer.class.isAssignableFrom(method.getReturnType()))
            .map(method -> {
                Bean bean = method.getAnnotation(Bean.class);
                if (bean == null) {
                    return null;
                }
                return bean.value().length > 0 && !bean.value()[0].isBlank() ? bean.value()[0] : method.getName();
            })
            .filter(Objects::nonNull)
            .sorted()
            .toList();
    }

    @Test
    void theAccountBindingReadsTheRegistrationTopic() {
        Map<String, Object> binding = bindings().get(ACCOUNT_BINDING);

        assertThat(binding).as("%s is not declared, so the step-1 verdict would reach nothing", ACCOUNT_BINDING).isNotNull();
        assertThat(binding).containsEntry("destination", REGISTRATION_TOPIC).containsEntry("content-type", "application/json");
    }

    @Test
    void theEntityBindingReadsTheEstatesEntityChannel() {
        Map<String, Object> binding = bindings().get(ENTITY_BINDING);

        assertThat(binding).as("%s is not declared, so steps 2-4 would never refresh", ENTITY_BINDING).isNotNull();
        assertThat(binding).containsEntry("destination", ENTITY_CHANGE_TOPIC).containsEntry("content-type", "application/json");
    }

    /**
     * ⛔ <b>No two INBOUND bindings share a consumer group.</b>
     *
     * <p>Two consumers sharing a group compete for partitions, so each sees roughly half the events —
     * which presents as "the meter refreshes about half the time" and "notifications work about half
     * the time", with nothing logged and nothing to point at. {@code pushEvents-in-0}'s own comment in
     * the shipped file records that this is how the defect shows up.
     *
     * <p>Asserted across <b>every</b> {@code -in-0} binding rather than between the two new ones,
     * because the mistake that matters is a new binding copying an existing group rather than the two
     * new ones agreeing with each other.
     *
     * <p>⚠ <b>Scoped to inbound bindings, and the reason is a pre-existing condition this change does
     * not touch.</b> {@code binding-out-0}, {@code kafkaProducer-out-0} and {@code kafkaConsumer-in-0}
     * all declare {@code group: hc-professional-ms} — the generated scaffold, which is dormant
     * (neither {@code kafkaConsumer} nor {@code kafkaProducer} is in the function definition). On an
     * <em>output</em> binding {@code group} is inert, so two producers sharing one costs nothing; the
     * duplicate is cosmetic and is not row 230's to resolve. Narrowing the assertion rather than
     * editing three unrelated lines is the deliberate choice, and it is recorded here so that a reader
     * who widens it knows what they will find.
     */
    @Test
    void noTwoInboundBindingsShareAConsumerGroup() {
        List<String> groups = bindings()
            .entrySet()
            .stream()
            .filter(binding -> binding.getKey().endsWith("-in-0"))
            .map(binding -> binding.getValue().get("group"))
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .toList();

        assertThat(groups).as("two consumers in one group each see about half the events").doesNotHaveDuplicates();
        assertThat(groups).contains("hc-professional-ms-meter", "hc-professional-ms-meter-changes");
    }

    /**
     * ⛔ The meter does not read a topic it could be confused with. The registration topic is the
     * clinician channel and {@code professional.event} is the row channel, and the two carry different
     * meanings of {@code subject} — see {@code EstateEventEnvelope}, which exists for that distinction.
     * Pointing either binding at the other's topic would bind a consumer to frames whose subject means
     * something else, and nothing would fail: it would simply never resolve an account.
     */
    @Test
    void theTwoMeterBindingsDoNotShareATopic() {
        assertThat(bindings().get(ACCOUNT_BINDING).get("destination")).isNotEqualTo(bindings().get(ENTITY_BINDING).get("destination"));
    }

    private Object functionDefinition() {
        Object definition = descendInShippedFile("spring", "cloud", "function", "definition");
        assertThat(definition).as("no spring.cloud.function.definition in %s", SHIPPED.toAbsolutePath()).isNotNull();
        return definition;
    }

    /** @see EntityChangeBindingTest#bindings the multi-document reasoning, which applies here too */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> bindings() {
        Object bindings = descendInShippedFile("spring", "cloud", "stream", "bindings");
        assertThat(bindings).as("no spring.cloud.stream.bindings block in %s", SHIPPED.toAbsolutePath()).isNotNull();
        return (Map<String, Map<String, Object>>) bindings;
    }

    /**
     * {@code loadAll} rather than {@code load}: this is a multi-document YAML — Spring separates its
     * profile-specific sections with {@code ---}, and SnakeYAML's single-document {@code load} throws
     * on it rather than returning the first.
     */
    private Object descendInShippedFile(String... keys) {
        try (InputStream in = Files.newInputStream(SHIPPED)) {
            for (Object document : new Yaml().loadAll(in)) {
                Object found = descend(document, keys);
                if (found != null) {
                    return found;
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not read the shipped configuration at " + SHIPPED.toAbsolutePath(), e);
        }
        return null;
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
