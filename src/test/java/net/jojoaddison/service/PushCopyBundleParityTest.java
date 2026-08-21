package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import net.jojoaddison.config.PushNotificationConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The four push copy bundles say the same things (MOB10).
 *
 * <p><b>Why this test has to exist.</b> Four languages is a shipping condition for this app, and
 * {@code mobile/} gates it three ways — {@code catalogues.spec.ts} compares key sets,
 * {@code untranslated-literals.spec.ts} proves the screens use them, {@code login.page.spec.ts}
 * renders against the real catalogues. None of them can see these files. Deciding that {@code api/}
 * composes push copy moved one set of user-visible strings out from under all three gates, and the
 * plan says so explicitly: without a parity check here the same drift returns in a place with no
 * gate at all. The failure mode is silent — a missing key renders as the key itself in the tray, and
 * a missing <em>bundle</em> renders as English to a clinician who chose German.
 */
class PushCopyBundleParityTest {

    private static final List<String> LANGUAGES = List.of("en", "es", "fr", "de");

    private final PushCopyService copy = new PushCopyService(new PushNotificationConfiguration().pushMessageSource());

    private static Properties bundle(String language) {
        Properties properties = new Properties();
        try (InputStream stream = PushCopyBundleParityTest.class.getResourceAsStream("/i18n/push_" + language + ".properties")) {
            assertThat(stream).as("i18n/push_%s.properties must exist", language).isNotNull();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("Could not read the " + language + " push bundle", e);
        }
        return properties;
    }

    static Stream<String> languages() {
        return LANGUAGES.stream();
    }

    @ParameterizedTest
    @MethodSource("languages")
    void everyBundleHasExactlyTheKeysEnglishHas(String language) {
        Set<String> english = bundle("en").stringPropertyNames();
        Set<String> other = bundle(language).stringPropertyNames();

        assertThat(other).as("keys missing from %s", language).containsAll(english);
        assertThat(english).as("keys in %s that English does not have", language).containsAll(other);
    }

    @ParameterizedTest
    @MethodSource("languages")
    void noValueIsBlank(String language) {
        // A blank value is worse than an untranslated one: the tray row arrives with no text at all,
        // and nothing in the pipeline treats it as an error.
        bundle(language).forEach((key, value) -> assertThat(value.toString().trim()).as("%s is blank in %s", key, language).isNotEmpty());
    }

    @ParameterizedTest
    @MethodSource("languages")
    void theSenderPlaceholderSurvivesTranslation(String language) {
        // Translating "New message from {0}" as "Nuevo mensaje" loses the name silently — the
        // notification still renders, just without the thing the recipient opted in to see.
        assertThat(bundle(language).getProperty("push.message.body.named")).as("%s named body", language).contains("{0}");
    }

    @ParameterizedTest
    @MethodSource("languages")
    void everyKeyRendersThroughTheMessageSource(String language) {
        // Reading the file is not proof the bundle is reachable: a wrong basename, a missing
        // encoding or fallbackToSystemLocale left on all fail here and nowhere else.
        for (String key : bundle("en").stringPropertyNames()) {
            List<String> args = bundle("en").getProperty(key).contains("{0}") ? List.of("Dr Mensah") : List.of();
            PushCopyService.Copy rendered = copy.render("push.message.title", key, args, language);
            assertThat(rendered.body()).as("%s in %s", key, language).isNotBlank().isNotEqualTo(key);
        }
    }

    @Test
    void aParameterisedValueDoublesITSapostrophes() {
        // MessageFormat treats ' as an escape, and only in values it actually parses — which is the
        // ones reached WITH arguments. So "Nouveau message d'{0}" renders as "Nouveau message d{0}"
        // with the name gone, while the identical quoting in an argument-less value is correct as
        // written. The two cases cannot be quoted the same way, nothing warns, and French and
        // Spanish copy is where an apostrophe will eventually be written. Only '' is legal here.
        for (String language : LANGUAGES) {
            String raw = bundle(language).getProperty("push.message.body.named");

            assertThat(raw.replace("''", ""))
                .as("%s: an apostrophe in a value MessageFormat parses must be doubled ('')", language)
                .doesNotContain("'");

            assertThat(copy.render("push.message.title", "push.message.body.named", List.of("Dr Mensah"), language).body())
                .as("%s names the sender", language)
                .contains("Dr Mensah");
        }
    }
}
