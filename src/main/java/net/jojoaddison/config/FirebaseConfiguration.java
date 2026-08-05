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
 * <p>APNs is reached through FCM — the APNs auth key is uploaded to the Firebase console — so this
 * one credential covers both platforms.
 */
@Configuration
@ConditionalOnProperty(prefix = "application.notifications.push", name = "enabled", havingValue = "true")
public class FirebaseConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfiguration.class);

    private final ApplicationProperties applicationProperties;

    public FirebaseConfiguration(ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    @Bean
    public FirebaseApp firebaseApp() throws Exception {
        if (!FirebaseApp.getApps().isEmpty()) {
            // Tests and hot restarts can initialise twice; the SDK throws on a duplicate name.
            return FirebaseApp.getInstance();
        }

        String path = applicationProperties.getNotifications().getPush().getCredentialsPath();
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
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }
}
