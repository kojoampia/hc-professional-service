package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.service.PatientServiceUnavailableException.Fault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
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
     * <b>The other untrue clause in the same sentence, one to the left</b> (backlog item 112). Item 107
     * fixed the retryability and left the opening as <em>"patientservice could not be read
     * (/api/clinical-cases)"</em> — which the sibling had just disproved by answering. "Could not be read"
     * is a claim about a service's health, and it is the claim that sends an operator to hc-patient's
     * logs, its container and its network before anyone looks at a scope-of-practice matrix. For the
     * three refused disciplines it is the message that reaches the screen on every strict read.
     *
     * <p>Asserted as a denial plus a replacement rather than on the whole string: the sentence is
     * composed from four parts and pinning it verbatim would go red on a reworded description, which is
     * not what this is about.
     */
    @Test
    void aREFUSALdoesNotClaimTheSiblingCouldNotBeRead() {
        String message = PatientServiceUnavailableException.read("/api/clinical-cases", Fault.of(forbidden()), "Forbidden").getMessage();

        assertThat(message).doesNotContain("could not be read").contains("refused this caller's discipline", "/api/clinical-cases");
    }

    /**
     * And the tail, which only misleads in company: {@code will NOT clear on retry} is shared with
     * {@link Fault#SCHEMA} and {@link Fault#NO_TOKEN}, where it means <em>somebody must change this
     * service first</em>. For a refusal it means the opposite. The words item 107 chose are kept and the
     * distinction is appended, so an operator who has learned the phrase still recognises it.
     */
    @Test
    void aREFUSALsaysThatNothingIsBroken() {
        assertThat(PatientServiceUnavailableException.read("/api/activity-logs", Fault.of(forbidden()), null).getMessage()).contains(
            "will NOT clear on retry, because nothing is broken"
        );
    }

    /**
     * <b>Nothing else moved, and this is the control for the two above.</b> A failure that really is a
     * failure still says so: rewriting the opening clause for every fault would have made a genuine
     * outage read as an authorisation decision, which is item 107's defect inverted and worse — an
     * operator told "nothing is broken" while the sibling is down.
     */
    @Test
    void aGENUINEfailureStillSaysTheReadDidNotHappen() {
        String message = PatientServiceUnavailableException.read(
            "/api/clinical-cases",
            Fault.of(new ResourceAccessException("connection refused")),
            null
        ).getMessage();

        assertThat(message)
            .contains("could not be read", "may clear on retry")
            .doesNotContain("refused this caller's discipline", "nothing is broken");
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
     * <b>And the sentence a 401 renders, which the test above does not reach</b> (backlog item 129).
     *
     * <p>Safe by construction — {@code message()} branches on {@code isAuthorisationRefusal()}, and the
     * test above pins that a 401 is not one — so this is belt-and-braces rather than a hole, and it is
     * written down as such. It is worth the three lines because the two facts are one edit apart and the
     * edit is tempting: a 401 and a 403 look alike at the call site, and the sentence item 112 wrote for a
     * refusal is exactly the wrong one here. "The sibling refused this caller's discipline" says a rule
     * worked; a 401 means the token this service <em>relayed</em> was rejected, which is the shared
     * signing key having drifted between the three stacks and is somebody's to fix tonight.
     *
     * <p>Asserted on the whole sentence and its title together, because item 127 found that the two can
     * disagree and nothing was looking at the second one.
     */
    @Test
    void a401READSlikeAFailureAndNotLikeARefusal() {
        PatientServiceUnavailableException raised = PatientServiceUnavailableException.read(
            "/api/clinical-cases",
            Fault.of(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null)),
            "Unauthorized"
        );

        assertThat(raised.getMessage())
            .doesNotContain("refused this caller's discipline", "nothing is broken")
            .contains("could not be read");
        assertThat(raised.title()).isEqualTo(PatientServiceUnavailableException.UNREACHABLE_TITLE);
    }

    // --- The title beside the message, which was the other half of the sentence (item 127) -------

    /**
     * <b>The clause items 107 and 112 did not reach, because it is not in the message</b> (backlog item
     * 127).
     *
     * <p>{@code ExceptionTranslator} builds the problem {@code detail} from {@code getMessage()} and the
     * {@code title} from the {@code @ResponseStatus} {@code reason}, which is a class-level annotation and
     * so was one string for every fault. Measured on the quality stack 2026-09-15, a technician asking for
     * one patient's cases got a {@code title} of <em>"The patient service could not be reached"</em> above
     * a {@code detail} saying the sibling refused them — the two prose fields of one document contradicting
     * each other, with the false one first.
     *
     * <p>Asserted on both fields in one test, deliberately: the defect was not that either sentence was
     * wrong on its own but that they disagreed, and two separate tests would each have passed throughout.
     */
    @Test
    void aREFUSALisTITLEDaRefusalAndNotAnUnreachableService() {
        PatientServiceUnavailableException raised = PatientServiceUnavailableException.read(
            "/api/clinical-cases",
            Fault.of(forbidden()),
            "Forbidden"
        );

        assertThat(raised.title())
            .isEqualTo(PatientServiceUnavailableException.REFUSED_TITLE)
            .isNotEqualTo(PatientServiceUnavailableException.UNREACHABLE_TITLE)
            .doesNotContain("could not be reached");
        assertThat(raised.getMessage()).contains("refused this caller's discipline");
    }

    /**
     * <b>The positive control, and the half this change is most likely to break.</b> A sibling that really
     * is unreachable must still be titled unreachable — retitling every 503 as a refusal would tell an
     * operator that nothing is broken while hc-patient is down, which is item 107's defect inverted and
     * worse than the one item 127 removed.
     */
    @Test
    void aGENUINEfailureIsStillTITLEDunreachable() {
        PatientServiceUnavailableException raised = PatientServiceUnavailableException.read(
            "/api/clinical-cases",
            Fault.of(new ResourceAccessException("connection refused")),
            null
        );

        assertThat(raised.title()).isEqualTo(PatientServiceUnavailableException.UNREACHABLE_TITLE);
        assertThat(raised.getMessage()).contains("could not be read");
    }

    /**
     * <b>The annotation and the method say the same thing, and this is what stops them drifting.</b> The
     * {@code reason} is what an uncaught one resolves through and the fallback arm of {@code title()} is
     * what the advice prefers; they are one constant today, and a later edit that inlines a literal into
     * either would leave two sentences for one condition. Read from the annotation by reflection rather
     * than re-spelled here, so this asserts the wiring and not a copy of it.
     */
    @Test
    void theRESPONSEstatusReasonIsTheSameSentenceAsTheFallbackTitle() {
        ResponseStatus annotated = PatientServiceUnavailableException.class.getAnnotation(ResponseStatus.class);

        assertThat(annotated).isNotNull();
        assertThat(annotated.reason()).isEqualTo(PatientServiceUnavailableException.UNREACHABLE_TITLE);
        assertThat(annotated.value()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Which title each fault takes — <b>hand-listed in a {@code switch} expression with no {@code default}
     * arm, which is the whole of what makes this more than a restatement of the implementation</b>.
     *
     * <p><b>The first version derived the expectation from {@link Fault#isAuthorisationRefusal()} and
     * claimed a ninth fault would therefore be "covered the day it is declared". It would not, and the
     * review of PR #52 proved it by adding one: twenty-one tests, zero failures.</b> An expectation that is
     * the same ternary as the implementation cannot disagree with it, so a new constant is
     * <em>iterated</em> and never <em>checked</em> — it slides in and takes {@code UNREACHABLE_TITLE} in
     * silence, which is precisely the outcome that sentence promised to prevent. By this repository's own
     * standard — backlog item 112's review, <em>a document asserting a property the code lacks is itself a
     * defect</em> — the javadoc was the defect rather than the assertion.
     *
     * <p>{@code blocksRecord()}'s tests have the shape that does work, and it is borrowed rather than
     * reinvented: a switch <em>expression</em> is exhaustive-checked at compile time, so a ninth
     * {@link Fault} <b>breaks this build</b> until somebody decides whether it is a refusal — a decision
     * forced where the constant is declared instead of an arm quietly inherited. Verified the way the
     * review falsified the old one, by adding a ninth: it no longer compiles.
     *
     * <p><b>The property the old version genuinely did hold is kept, because this listing is not the
     * implementation's predicate.</b> {@code title()} is a function of {@code isAuthorisationRefusal()} and
     * of nothing else — re-keying it on {@code !clearsOnRetry()}, the neighbouring flag and the plausible
     * mistake, reddens {@link Fault#SCHEMA} and {@link Fault#NO_TOKEN} here, which share that value with a
     * refusal while meaning something else entirely.
     */
    @ParameterizedTest
    @EnumSource(Fault.class)
    void everyFaultIsTitledByWhetherItIsARefusal(Fault fault) {
        // No `default`, deliberately: this is a decision table, not a shortcut. A `default` arm — or a
        // switch statement, which Java does not require to be exhaustive over constant labels — would
        // turn the compile error a ninth constant should cause back into a silently unexercised case.
        String expected =
            switch (fault) {
                case UPSTREAM_FORBIDDEN -> PatientServiceUnavailableException.REFUSED_TITLE;
                case TRANSPORT,
                    UPSTREAM_STATUS,
                    BUDGET_EXHAUSTED,
                    PAGE_GUARD,
                    SCHEMA,
                    NO_TOKEN,
                    UNKNOWN -> PatientServiceUnavailableException.UNREACHABLE_TITLE;
            };

        assertThat(PatientServiceUnavailableException.read("/api/clinical-cases", fault, null).title())
            .describedAs("%s.isAuthorisationRefusal() is %s", fault, fault.isAuthorisationRefusal())
            .isEqualTo(expected);
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
