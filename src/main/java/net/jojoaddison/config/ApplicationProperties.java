package net.jojoaddison.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties specific to Hc Professional Ms.
 * <p>
 * Properties are configured in the {@code application.yml} file.
 * See {@link tech.jhipster.config.JHipsterProperties} for a good example.
 */
@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
public class ApplicationProperties {

    private final Kafka kafka = new Kafka();
    private final Notifications notifications = new Notifications();

    // jhipster-needle-application-properties-property

    public Kafka getKafka() {
        return kafka;
    }

    public Notifications getNotifications() {
        return notifications;
    }

    // jhipster-needle-application-properties-property-getter

    /**
     * Whether this service emits domain events at all.
     * <p>
     * Deployments that run no broker set {@code application.kafka.enabled=false}. Without it the
     * absence of Kafka is reported as a failure rather than as configuration: every write logs an
     * ERROR with a stack trace from {@code DomainEventPublisher}, and Spring Cloud Stream's
     * {@code BindingService} retries creating the producer binding every 30 seconds indefinitely —
     * roughly 2,900 ERROR lines a day on an idle stack, which is enough to bury a real one.
     * <p>
     * This is deliberately not a Spring {@code @ConditionalOnProperty} on the publisher bean: it is
     * injected in fourteen places, so making the bean conditional would mean making all fourteen
     * handle its absence. The flag lives inside the publisher instead, which keeps the decision in
     * one place.
     */
    public static class Kafka {

        /**
         * Defaults to true so the shipped configuration, the integration tests and any deployment
         * that does have a broker are unaffected — only a stack that has consciously left Kafka out
         * turns it off.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Push notification settings (MOB9).
     *
     * <p>Disabled by default, mirroring {@link Kafka}: a stack with no push credentials must start
     * cleanly rather than log a stack trace per event. Absence of credentials is a supported
     * configuration, not a failure.
     *
     * <p><b>Two transports, on purpose.</b> iOS goes straight to APNs and Android goes through FCM.
     * Apple offers no alternative to APNs and Google effectively none to FCM on certified devices,
     * but neither requires Firebase on the iOS side — and {@code @capacitor/push-notifications}
     * hands back a raw APNs token there anyway, which FCM would reject. Talking to APNs directly
     * therefore removes a middleman rather than adding a credential: the same .p8 key would have to
     * be uploaded to Firebase to reach iOS regardless.
     */
    public static class Notifications {

        private final Push push = new Push();

        public Push getPush() {
            return push;
        }

        public static class Push {

            /**
             * Defaults to FALSE, unlike kafka.enabled. Kafka has always been part of the shipped
             * configuration; push credentials are not, and enabling push without them would warn
             * on every event for every deployment that has not set one up.
             */
            private boolean enabled = false;

            private final Fcm fcm = new Fcm();
            private final Apns apns = new Apns();

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public Fcm getFcm() {
                return fcm;
            }

            public Apns getApns() {
                return apns;
            }

            /** Android. */
            public static class Fcm {

                /** Service-account JSON. Empty falls back to GOOGLE_APPLICATION_CREDENTIALS. */
                private String credentialsPath = "";

                public String getCredentialsPath() {
                    return credentialsPath;
                }

                public void setCredentialsPath(String credentialsPath) {
                    this.credentialsPath = credentialsPath;
                }
            }

            /** iOS, spoken directly rather than through Firebase. */
            public static class Apns {

                /** Path to the .p8 token-signing key downloaded from the Apple developer portal. */
                private String keyPath = "";

                /** The 10-character Key ID that accompanies the .p8. */
                private String keyId = "";

                /** The 10-character Apple Team ID. */
                private String teamId = "";

                /** APNs topic — the app's bundle id. */
                private String bundleId = "com.abofonsa.bridgecare.professional";

                /**
                 * True targets api.push.apple.com, false the sandbox.
                 *
                 * <p>This is the classic APNs foot-gun: a TestFlight or debug build registers with
                 * the sandbox, and sending its token to the production host returns
                 * {@code BadDeviceToken} — which looks exactly like an expired token. Set it to
                 * match the build you are testing.
                 */
                private boolean production = true;

                public String getKeyPath() {
                    return keyPath;
                }

                public void setKeyPath(String keyPath) {
                    this.keyPath = keyPath;
                }

                public String getKeyId() {
                    return keyId;
                }

                public void setKeyId(String keyId) {
                    this.keyId = keyId;
                }

                public String getTeamId() {
                    return teamId;
                }

                public void setTeamId(String teamId) {
                    this.teamId = teamId;
                }

                public String getBundleId() {
                    return bundleId;
                }

                public void setBundleId(String bundleId) {
                    this.bundleId = bundleId;
                }

                public boolean isProduction() {
                    return production;
                }

                public void setProduction(boolean production) {
                    this.production = production;
                }

                /** Usable only when every piece of the credential is present. */
                public boolean isConfigured() {
                    return !keyPath.isBlank() && !keyId.isBlank() && !teamId.isBlank() && !bundleId.isBlank();
                }
            }
        }
    }
    // jhipster-needle-application-properties-property-class
}
