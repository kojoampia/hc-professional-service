package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A push credential that cannot be read must disable push, not stop the service.
 *
 * <p>This exists because of a real outage. On 2026-08-11 the Firebase service-account file was
 * bind-mounted into the container owned by root with mode 0600; the JVM runs as {@code uid 100} and
 * could not read it. The exception escaped the {@code @Bean} method, Spring aborted the context, and
 * the API crash-looped — taking every clinical endpoint down because a <em>notification channel</em>
 * was misconfigured.
 *
 * <p>"Credential absent" was already handled and was the case everybody thought about. "Credential
 * present but unreadable" is the likelier operational mistake and was the one that was fatal.
 */
class FirebaseConfigurationUnitTest {

    private ApplicationProperties propertiesWithCredentialPath(String path) {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getNotifications().getPush().getFcm().setCredentialsPath(path);
        return properties;
    }

    /** Stands in for Spring's provider; the real one behaves the same way for an absent bean. */
    private ObjectProvider<com.google.firebase.FirebaseApp> absentApp() {
        return new ObjectProvider<>() {
            @Override
            public com.google.firebase.FirebaseApp getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.google.firebase.FirebaseApp getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.google.firebase.FirebaseApp getIfAvailable() {
                return null;
            }

            @Override
            public com.google.firebase.FirebaseApp getIfUnique() {
                return null;
            }
        };
    }

    @Test
    void anUnreadableCredentialDisablesPushRatherThanFailingStartup() {
        // A path that cannot be opened — the same class of failure as a root-owned 0600 mount.
        FirebaseConfiguration configuration = new FirebaseConfiguration(
            propertiesWithCredentialPath("/definitely/not/a/readable/credential.json")
        );

        assertThatCode(configuration::firebaseApp)
            .as("a bad credential must not propagate out of the bean method and abort the context")
            .doesNotThrowAnyException();
        assertThat(configuration.firebaseApp()).as("no FirebaseApp means push is simply off").isNull();
    }

    @Test
    void aMalformedCredentialIsTreatedTheSameWay() throws Exception {
        // Not just missing: a file that exists and is readable but is not a service account. Reading
        // one of those used to abort startup exactly as an unreadable one did.
        java.nio.file.Path junk = java.nio.file.Files.createTempFile("not-a-credential", ".json");
        java.nio.file.Files.writeString(junk, "{\"this\":\"is not a service account\"}");
        try {
            FirebaseConfiguration configuration = new FirebaseConfiguration(propertiesWithCredentialPath(junk.toString()));

            assertThatCode(configuration::firebaseApp).doesNotThrowAnyException();
            assertThat(configuration.firebaseApp()).isNull();
        } finally {
            java.nio.file.Files.deleteIfExists(junk);
        }
    }

    @Test
    void messagingIsAbsentWhenTheAppIsAbsentRatherThanThrowing() {
        // The second half of the chain. If this demanded a FirebaseApp, an absent one would fail the
        // context for the same reason the original bug did, just one bean later.
        FirebaseConfiguration configuration = new FirebaseConfiguration(propertiesWithCredentialPath(""));

        assertThat(configuration.firebaseMessaging(absentApp())).isNull();
    }
}
