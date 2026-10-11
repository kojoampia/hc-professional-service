package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * ⛔ <b>That the heartbeat is actually scheduled, and that its interval resolves</b> — backlog.md row
 * 230 unit B, added after review.
 *
 * <h2>Why this test has to exist separately from {@code OnboardingProgressStreamIT}</h2>
 *
 * <p>{@code OnboardingProgressStreamIT} calls {@link OnboardingProgressStream#sendHeartbeats()}
 * directly, which proves what the method <em>does</em> and nothing about whether anything ever calls
 * it. ⛔ <b>And no integration test in this repository can prove that</b>: {@code pom.xml} activates
 * the {@code testdev} profile for failsafe, {@code AsyncConfiguration} — the class carrying
 * {@code @EnableScheduling} — is annotated {@code @Profile("!testdev & !testprod")}, so <b>the IT
 * context has no {@link ScheduledTaskHolder} at all</b> and the annotation is inert there.
 *
 * <p>⚠ <b>So the SpEL in {@code fixedRateString} was evaluated by nothing.</b> That matters more than
 * it sounds: an unresolvable {@code fixedRateString} fails the {@code ApplicationContext} at startup,
 * which is backlog rows 50 and 86 one layer over — a production-only configuration error that
 * {@code clean verify} cannot see, because the thing that would catch it is switched off in tests.
 * This test switches scheduling on in a context of its own and asks the question directly.
 *
 * <p>⭐ <b>No Mongo, no Testcontainers, no application context of the real app</b> — two beans and
 * {@code @EnableScheduling}. It runs in well under a second, which is the only reason it can afford to
 * exist as a unit test rather than as a thirteenth integration test on a cold replica set.
 */
@SpringJUnitConfig(OnboardingProgressHeartbeatSchedulingTest.SchedulingOnlyConfig.class)
class OnboardingProgressHeartbeatSchedulingTest {

    /**
     * The smallest context that can answer the question: scheduling enabled, and the one bean whose
     * annotation is under test.
     */
    @Configuration
    @EnableScheduling
    static class SchedulingOnlyConfig {

        @Bean
        OnboardingProgressStream onboardingProgressStream() {
            return new OnboardingProgressStream();
        }
    }

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    /**
     * ⭐ <b>The heartbeat is registered as a fixed-rate task, and its SpEL interval resolves to 30s.</b>
     *
     * <p>Reading the interval back rather than merely counting tasks is the point: a
     * {@code fixedRateString} that does not resolve does not register a task with a wrong interval, it
     * <b>fails the context</b> — so this test failing to start is itself the signal, and the duration
     * assertion is what catches a silently edited constant.
     */
    @Test
    void theHeartbeatIsScheduledAtTheIntervalTheConstantNames() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
            .as("the heartbeat must be registered as a scheduled task")
            .isNotEmpty()
            .anySatisfy(
                task ->
                    assertThat(intervalOf(task))
                        .as("the fixedRateString SpEL must resolve to HEARTBEAT_MILLIS")
                        .isEqualTo(Duration.ofMillis(OnboardingProgressStream.HEARTBEAT_MILLIS))
            );

        assertThat(Duration.ofMillis(OnboardingProgressStream.HEARTBEAT_MILLIS)).isEqualTo(Duration.ofSeconds(30));
    }

    /**
     * ⛔ <b>The interval must stay under nginx's {@code proxy_read_timeout 120s}, and nothing else in
     * the repository checks that.</b>
     *
     * <p>{@link OnboardingProgressStream#HEARTBEAT_MILLIS} is read by exactly two things: the
     * {@code @Scheduled} annotation and its own javadoc. So raising it past the ceiling it exists to
     * stay under is currently a silent change — every test stays green while idle streams start being
     * cut again, in deployed environments only.
     *
     * <p>⚠ <b>The 120 000 here is a copy of a value that lives in another repository</b>
     * ({@code deploy/docker/web-nginx.conf}) and nothing can assert across that boundary from here —
     * the same one-layer-short shape backlog row 234 records for {@code MeterBindingTest}. What this
     * buys is that the constant cannot drift <em>past a stated ceiling</em> unnoticed; if the ceiling
     * itself moves in {@code deploy/}, this number is wrong and no test anywhere will say so.
     *
     * <p>A quarter of the ceiling is asserted rather than merely "less than", so <b>two</b> consecutive
     * lost heartbeats are still comfortably inside the window.
     *
     * <p>⚠ <b>Two, not three</b> — this said three, and the arithmetic does not support it: at 30s
     * against 120s the write following three consecutive losses lands at <b>exactly t=120s</b>, which
     * is the boundary rather than inside it. The quarter-margin is still the right constraint; the
     * claim about it was off by one.
     */
    @Test
    void theIntervalStaysWellUnderTheProxyReadTimeout() {
        long nginxProxyReadTimeoutMillis = 120_000L;

        assertThat(OnboardingProgressStream.HEARTBEAT_MILLIS)
            .as("a heartbeat at or above nginx's read timeout cannot keep an idle stream alive")
            .isLessThan(nginxProxyReadTimeoutMillis)
            .isLessThanOrEqualTo(nginxProxyReadTimeoutMillis / 4);
    }

    private static Duration intervalOf(ScheduledTask task) {
        return task.getTask() instanceof IntervalTask interval ? interval.getIntervalDuration() : null;
    }
}
