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
     * <p>Disabled by default, mirroring {@link Kafka}: a stack with no Firebase project must start
     * cleanly rather than log a stack trace per event. Absence of credentials is a supported
     * configuration, not a failure.
     */
    public static class Notifications {

        private final Push push = new Push();

        public Push getPush() {
            return push;
        }

        public static class Push {

            /**
             * Defaults to FALSE, unlike kafka.enabled. Kafka has always been part of the shipped
             * configuration; Firebase is not, and enabling push without credentials would produce
             * a warning per event on every deployment that has not set one up.
             */
            private boolean enabled = false;

            /** Path to the Firebase service-account JSON. Empty falls back to GOOGLE_APPLICATION_CREDENTIALS. */
            private String credentialsPath = "";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getCredentialsPath() {
                return credentialsPath;
            }

            public void setCredentialsPath(String credentialsPath) {
                this.credentialsPath = credentialsPath;
            }
        }
    }
    // jhipster-needle-application-properties-property-class
}
