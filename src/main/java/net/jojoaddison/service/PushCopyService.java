package net.jojoaddison.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;

/**
 * Renders push notification text in the language of the device that will receive it (MOB10).
 *
 * <p><b>Why the server composes the copy at all.</b> The first design sent
 * {@code title_loc_key}/{@code body_loc_key} and let the handset resolve them. That cannot work and
 * must not be built on again. Android looks a {@code title_loc_key} up as a <em>string resource</em>
 * and resource names may not contain dots, so {@code push.message.title} is not a legal name — and
 * there are no {@code values-es/}, {@code values-fr/} or {@code values-de/} directories to resolve
 * it in even if it were. iOS never reads the keys at all: {@code ApnsClient} is handed the finished
 * strings. So every locale got the English fallback, on both platforms, silently.
 *
 * <p><b>The language is per device, not per account.</b> A clinician may carry an English-configured
 * work handset and a German personal one, and each should notify in its own language.
 * {@code DeviceToken.langKey} is set at registration and refreshed when the picker changes.
 *
 * <p><b>This is the one set of user-visible strings outside {@code mobile/}'s catalogues</b>, which
 * is the accepted cost of the decision — and it means {@code catalogues.spec.ts} cannot see them.
 * {@code PushCopyBundleParityTest} is their gate instead. See
 * {@code mobile-app-plan.md § Push copy is composed by the server}.
 */
@Service
public class PushCopyService {

    /** The four languages the app ships. Anything else falls back to English. */
    static final Set<String> SUPPORTED = Set.of("en", "es", "fr", "de");

    /** A notification in the wrong language beats none, so an unknown langKey is English. */
    static final String FALLBACK = "en";

    private final MessageSource pushMessages;

    public PushCopyService(@Qualifier("pushMessageSource") MessageSource pushMessages) {
        this.pushMessages = pushMessages;
    }

    /** Finished text for one notification, ready to hand to FCM or APNs. */
    public record Copy(String title, String body) {}

    /**
     * Normalises a device's {@code langKey} to one of the four shipped languages.
     *
     * <p>Only the primary subtag counts: {@code fr-CA} and {@code fr_FR} both select {@code fr},
     * matching {@code LanguageService} on the client. Null, blank and unknown all become English.
     */
    public String normalise(String langKey) {
        if (langKey == null || langKey.isBlank()) {
            return FALLBACK;
        }
        String primary = langKey.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return SUPPORTED.contains(primary) ? primary : FALLBACK;
    }

    /**
     * Renders one notification's title and body.
     *
     * @param titleCode bundle key for the title
     * @param bodyCode bundle key for the body
     * @param bodyArgs {@code MessageFormat} arguments for the body, may be empty
     * @param language a value already through {@link #normalise(String)}
     */
    public Copy render(String titleCode, String bodyCode, List<String> bodyArgs, String language) {
        Locale locale = Locale.of(normalise(language));
        return new Copy(resolve(titleCode, List.of(), locale), resolve(bodyCode, bodyArgs, locale));
    }

    /**
     * One key.
     *
     * <p>Never throws. A missing key is a copy defect, not a reason to drop a clinician's
     * notification — the code itself is a poor notification but a notification, and it names the
     * bundle entry to add. The bundles are gated by a parity test precisely so this stays theoretical.
     */
    private String resolve(String code, List<String> args, Locale locale) {
        try {
            return pushMessages.getMessage(code, args.isEmpty() ? null : args.toArray(), locale);
        } catch (NoSuchMessageException e) {
            return code;
        }
    }
}
