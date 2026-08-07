package net.jojoaddison.config;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Suppresses the Kafka binder's own consumer-lag gauge, and with it a misleading log line.
 *
 * <p><b>The symptom.</b> Since MOB9 added a second consumer binding, the service logged this once a
 * minute, forever:
 *
 * <pre>
 * WARN o.a.k.c.c.internals.OffsetFetcherUtils : [Consumer clientId=consumer-hc-professional-ms-push-5,
 *   groupId=hc-professional-ms-push] Not updating high watermark for partition
 *   hc.professional.entity-0 as it is no longer assigned
 * </pre>
 *
 * <p><b>Why it is worth removing rather than tolerating.</b> It names a clientId in the push
 * consumer group and says a partition is not assigned, so it reads exactly like a broken push
 * pipeline. It is not one: the clientId it names is a metrics-only consumer that never joins the
 * group, while the real consumer holds the partition with zero lag. Anyone debugging a genuine
 * push failure would start from this line and lose time before discovering it is unrelated. That
 * is the cost being paid here — the noise itself is harmless.
 *
 * <p><b>The mechanism.</b> {@code KafkaBinderMetrics#bindTo} registers a
 * {@code spring.cloud.stream.binder.kafka.offset} gauge per consumer binding and then, unless the
 * registered gauge is a {@link io.micrometer.core.instrument.noop.NoopGauge}, schedules a task at
 * {@code offsetLagMetricsInterval} (default 60s) that calls {@code endOffsets} /
 * {@code beginningOffsets} / {@code committed} on a consumer it never assigns partitions to. Those
 * fetch responses are what {@code OffsetFetcherUtils} complains about.
 *
 * <p>The NoopGauge check is the only supported way off that path, which is why this is a
 * {@link MeterFilter} and not a property. Two routes that look right and are not:
 * {@code spring.cloud.stream.kafka.binder.metrics.default-offset-lag-metrics-enabled: false} only
 * switches the gauge between computing on read and reading a cached map — the scheduled task, and
 * therefore the warning, run either way; and {@code management.metrics.enable.*} no longer exists
 * in Spring Boot 4.1.
 *
 * <p><b>What is lost.</b> Only this gauge. The real consumers still report {@code records-lag}
 * through the OpenTelemetry Kafka metrics reporter already on their {@code metric.reporters} list,
 * so lag still reaches the collector — from the consumers that actually hold partitions, which is
 * the better signal. The broker remains the authority either way
 * ({@code kafka-consumer-groups.sh --describe --group hc-professional-ms-push}).
 */
@Configuration
public class KafkaBinderMetricsConfiguration {

    /** Meter name registered per consumer binding by {@code KafkaBinderMetrics}. */
    static final String BINDER_OFFSET_METER = "spring.cloud.stream.binder.kafka.offset";

    @Bean
    public MeterFilter kafkaBinderOffsetLagMeterFilter() {
        return MeterFilter.denyNameStartsWith(BINDER_OFFSET_METER);
    }
}
