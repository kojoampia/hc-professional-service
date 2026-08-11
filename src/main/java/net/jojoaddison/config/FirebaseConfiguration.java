package net.jojoaddison.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Firebase, wired only when push is switched on.
 *
 * <p>{@code @ConditionalOnProperty} rather than a runtime check inside the bean: with push
 * disabled there is no Firebase project, no credentials file, and nothing to construct.
 * {@code PushNotificationService} takes an {@code ObjectProvider}, so its absence is expected
 * rather than an injection failure.
 *
 * <p><b>Android only.</b> iOS is served by {@code ApnsClient} talking to Apple directly, so no
 * Firebase project configuration ships in the iOS app.
 *
 * <p><b>A bad credential disables push; it does not stop the service.</b> This used to let the
 * exception out of the bean method, which aborts the Spring context — so on 2026-08-11 a
 * bind-mounted credential file owned by root, unreadable to the container's {@code uid 100}, took
 * the whole API down in a crash loop. Push is a notification channel. It must not be able to stop
 * clinicians reading records, and "the file is there but I cannot read it" is a likelier mistake
 * than "the file is absent", which was already handled. Both now log and leave push off, matching
 * what {@code PushNotificationService} already expects from {@code getIfAvailable()}.
 */
@Configuration
@ConditionalOnProperty(prefix = "application.notifications.push", name = "enabled", havingValue = "true")
public class FirebaseConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfiguration.class);

    private final ApplicationProperties applicationProperties;

    public FirebaseConfiguration(ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    /**
     * @return the initialised app, or {@code null} when the credential cannot be read. Null is
     *     deliberate: Spring registers it as an absent bean, which is exactly what
     *     {@code PushNotificationService}'s {@code ObjectProvider.getIfAvailable()} is written for.
     */
    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            // Tests and hot restarts can initialise twice; the SDK throws on a duplicate name.
            return FirebaseApp.getInstance();
        }

        String path = applicationProperties.getNotifications().getPush().getFcm().getCredentialsPath();
        try {
            GoogleCredentials credentials;
            if (path == null || path.isBlank()) {
                // Falls back to GOOGLE_APPLICATION_CREDENTIALS, which is how the container is wired.
                credentials = GoogleCredentials.getApplicationDefault();
            } else {
                try (InputStream in = Files.newInputStream(Path.of(path))) {
                    credentials = GoogleCredentials.fromStream(in);
                }
            }
            log.info("Initialising Firebase for push notifications");
            return FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(credentials).build());
        } catch (Exception e) {
            // ERROR, not WARN: push was explicitly switched on and is not working, so this is a
            // misconfiguration someone must fix — but it is not a reason to refuse to serve.
            log.error(
                "Push is enabled but Firebase could not be initialised from '{}' — Android notifications are OFF and " +
                "the service is starting without them. If the file exists, check it is readable by the container user " +
                "(uid 100): a root-owned 0600 bind mount is the usual cause.",
                path == null || path.isBlank() ? "GOOGLE_APPLICATION_CREDENTIALS" : path,
                e
            );
            return null;
        }
    }

    /**
     * @return messaging bound to the app, or {@code null} when the app failed to initialise. Takes
     *     an {@code ObjectProvider} so that an absent {@code FirebaseApp} is a missing dependency
     *     rather than an unsatisfied one, which would fail the context for the same reason again.
     */
    @Bean
    public FirebaseMessaging firebaseMessaging(ObjectProvider<FirebaseApp> firebaseApp) {
        FirebaseApp app = firebaseApp.getIfAvailable();
        return app == null ? null : FirebaseMessaging.getInstance(app);
    }
}
