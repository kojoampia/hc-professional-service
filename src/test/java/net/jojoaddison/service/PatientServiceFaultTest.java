package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.service.PatientServiceUnavailableException.Fault;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * What an answered status from patientservice is classified as, and — the part this exists for —
 * whether the sentence an operator reads about it is true.
 *
 * <p><b>Backlog item 107.</b> {@code Fault.UPSTREAM_STATUS} was one bucket for every error status with
 * {@code clearsOnRetry} hardcoded {@code true}, so a 403 — hc-patient's permanent, deliberate
 * scope-of-practice decision about who is asking — was rendered as <em>"the sibling answered an error
 * status; may clear on retry"</em>. That sentence sent whoever read it to retry, then to the network,
 * then to the sibling's health: three places, none of them the answer.
 *
 * <p><b>Each assertion below moves one thing.</b> The two statuses are separate tests because an
 * aggregate green cannot tell "the split works" from "everything became non-retryable", and the
 * retryability of each is asserted on the rendered message as well as on the flag, because the message
 * is what an operator actually reads and it is composed rather than stored.
 */
class PatientServiceFaultTest {

    private static HttpClientErrorException forbidden() {
        return HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null);
    }

    /**
     * The defect, stated as a test: a 403 is an authorisation answer and no amount of waiting changes
     * it. Asserted on the rendered message too — {@code clearsOnRetry()} alone would have been green on
     * a fix that classified correctly and went on printing the wrong sentence.
     */
    @Test
    void aREFUSALisNeverDescribedAsSomethingThatMightClearOnRetry() {
        Fault fault = Fault.of(forbidden());

        assertThat(fault).isEqualTo(Fault.UPSTREAM_FORBIDDEN);
        assertThat(fault.clearsOnRetry()).isFalse();
        assertThat(fault.isAuthorisationRefusal()).isTrue();
        assertThat(PatientServiceUnavailableException.read("/api/activity-logs", fault, "Forbidden").getMessage())
            .contains("will NOT clear on retry")
            .doesNotContain("may clear on retry");
    }

    /**
     * And the other half, which the fix is at least as likely to break: a sibling that is genuinely
     * unwell is still a transient fault, and telling an operator that a 502 will never clear is the
     * same defect pointing the other way.
     */
    @Test
    void aTRANSIENTupstreamStatusIsStillRetryable() {
        Fault fault = Fault.of(
            HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], null)
        );

        assertThat(fault).isEqualTo(Fault.UPSTREAM_STATUS);
        assertThat(fault.clearsOnRetry()).isTrue();
        assertThat(fault.isAuthorisationRefusal()).isFalse();
        assertThat(PatientServiceUnavailableException.read("/api/activity-logs", fault, "Bad Gateway").getMessage()).contains(
            "may clear on retry"
        );
    }

    /**
     * <b>401 is deliberately not a refusal</b>, and this is the test that stops the split widening to
     * "every 4xx". It does not mean the caller's role was refused — this service authenticated them —
     * it means the token this service <em>relayed</em> was not accepted over there, which in practice
     * is the shared signing key having drifted between the three stacks. That is an estate fault
     * somebody has to fix, not a rule working as designed, and {@code PatientServiceClient.failedWrite}
     * has treated it as the exception to its own 4xx pass-through since 2026-09-07 for the same reason.
     */
    @Test
    void a401IsNOTaScopeRefusal() {
        Fault fault = Fault.of(
            HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null)
        );

        assertThat(fault).isEqualTo(Fault.UPSTREAM_STATUS);
        assertThat(fault.isAuthorisationRefusal()).isFalse();
    }

    /**
     * A refusal wrapped by the HTTP stack is still a refusal — {@code Fault.of} walks the cause chain
     * rather than switching on the top type, and the 403 arriving nested is how it arrives in practice.
     */
    @Test
    void aREFUSALisFoundThroughTheCauseChain() {
        assertThat(Fault.of(new IllegalStateException("wrapped", forbidden()))).isEqualTo(Fault.UPSTREAM_FORBIDDEN);
    }

    /** Nothing else moved: a transport failure is classified as it always was. */
    @Test
    void aTransportFailureIsUnchanged() {
        assertThat(Fault.of(new ResourceAccessException("connection refused"))).isEqualTo(Fault.TRANSPORT);
    }
}
