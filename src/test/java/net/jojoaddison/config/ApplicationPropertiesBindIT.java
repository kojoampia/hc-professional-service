package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.yaml.snakeyaml.Yaml;

/**
 * Every {@code application.*} key in the <b>production</b> config binds to {@link ApplicationProperties}.
 *
 * <p><b>Why this exists, and why 453 green tests did not make it unnecessary.</b> Backlog item 50 added
 * {@code application.gateway.base-url} and {@code .timeout-seconds} to
 * {@code src/main/resources/config/application.yml} and read them with {@code @Value} defaults — which
 * works perfectly well on its own. But {@link ApplicationProperties} is
 * {@code @ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)}, so a key under
 * {@code application.} that nothing declares fails the bind, and a failed bind fails the entire
 * {@code ApplicationContext}:
 *
 * <pre>
 * Could not bind properties to 'ApplicationProperties' : prefix=application, ignoreUnknownFields=false
 *   Reason: The elements [application.gateway.base-url,application.gateway.timeout-seconds]
 *           were left unbound.
 * </pre>
 *
 * <p>The service did not start. Every request answered 500 because there was no application to answer
 * it — and the whole suite stayed green, because <b>tests load
 * {@code src/test/resources/config/application.yml}, which replaces the production file on the test
 * classpath and does not carry these keys</b>. That is the gap this class closes: a configuration
 * error that only exists in the artefact that ships.
 *
 * <p>It was caught by {@code quality/}'s {@code start} job — the one that runs a published image behind
 * a hostname — which is precisely the class of defect the workspace guide says that stack exists for:
 * *"none of that fails a unit test, a build, or `ng serve`"*. This test is the cheaper half of that
 * lesson, brought back into the repository where the mistake is made.
 *
 * <p>Reads the file by path rather than from the classpath, the same way {@code DocumentUploadLimitIT}
 * does and for the same reason: the classpath copy is the test one.
 *
 * <p>See backlog.md items 50 and 86.
 */
class ApplicationPropertiesBindIT {

    private static final Path PRODUCTION_CONFIG = Path.of("src/main/resources/config/application.yml");

    @Test
    void everyApplicationKeyInTheProductionConfigBindsToApplicationProperties() throws Exception {
        Map<String, Object> flattened = flattenedApplicationKeys();

        assertThat(flattened)
            .as("no application.* keys were found in %s — the test is not reading what it thinks it is", PRODUCTION_CONFIG)
            .isNotEmpty();

        // Exactly what Spring does at startup, against exactly the file that ships. A key nothing
        // declares raises here rather than six hours later in a container that will not start.
        // NoUnboundElementsBindHandler is what makes this a test rather than decoration. `Binder.bind`
        // alone ignores the class's `ignoreUnknownFields = false` — that annotation is read by Spring's
        // ConfigurationPropertiesBinder, not by Binder — so without the handler an unbound key binds
        // happily and this passes with the defect present. Found by mutating the fix away and watching
        // the first version of this test stay green.
        assertThatCode(
            () ->
                new Binder(new MapConfigurationPropertySource(flattened)).bind(
                    "application",
                    Bindable.of(ApplicationProperties.class),
                    new NoUnboundElementsBindHandler(BindHandler.DEFAULT)
                )
        )
            .as(
                "a key under `application.` in the production config binds to nothing on ApplicationProperties, which is ignoreUnknownFields=false — the service will not start"
            )
            .doesNotThrowAnyException();
    }

    /** The `application:` subtree of the production YAML, flattened to dotted keys. */
    private Map<String, Object> flattenedApplicationKeys() throws Exception {
        assertThat(Files.exists(PRODUCTION_CONFIG))
            .as("%s is missing — this test guards the file that ships, not the one tests load", PRODUCTION_CONFIG)
            .isTrue();

        Map<String, Object> flattened = new LinkedHashMap<>();
        try (InputStream yaml = Files.newInputStream(PRODUCTION_CONFIG)) {
            // The file is a multi-document YAML (Spring profiles), and a key may appear in any
            // document — so every document is flattened, not just the first.
            for (Object document : new Yaml().loadAll(yaml)) {
                if (document instanceof Map<?, ?> root && root.get("application") instanceof Map<?, ?> application) {
                    flatten("application", application, flattened);
                }
            }
        }
        return flattened;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<?, ?> node, Map<String, Object> into) {
        node.forEach((key, value) -> {
            String path = prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                flatten(path, (Map<Object, Object>) nested, into);
            } else if (value != null) {
                into.put(path, value);
            }
        });
    }
}
