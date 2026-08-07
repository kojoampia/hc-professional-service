package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the suppression of the Kafka binder's consumer-lag gauge — see {@link
 * KafkaBinderMetricsConfiguration} for why it exists.
 *
 * <p>This asserts against the live {@link MeterRegistry} rather than against the filter bean in
 * isolation, because the filter alone proves nothing: the behaviour that matters is that
 * {@code KafkaBinderMetrics#bindTo} sees a {@code NoopGauge} and therefore never schedules the
 * 60-second offset-query task. A registry with no such meter is the observable form of that.
 *
 * <p>If this fails, the once-a-minute {@code "Not updating high watermark ... no longer assigned"}
 * warning is back in the production logs, pointing at the push consumer group and implying a fault
 * that is not there.
 */
@IntegrationTest
class KafkaBinderMetricsConfigurationIT {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void binderOffsetLagGaugeIsNotRegistered() {
        assertThat(meterRegistry.getMeters())
            .as("the binder's consumer-lag gauge must stay unregistered so no offset-query task is scheduled")
            .noneMatch(meter -> meter.getId().getName().startsWith(KafkaBinderMetricsConfiguration.BINDER_OFFSET_METER));
    }

    @Test
    void otherMetersAreUnaffected() {
        assertThat(meterRegistry.getMeters())
            .as("the filter must be narrow — denying the one meter name, not metrics generally")
            .isNotEmpty();
    }
}
