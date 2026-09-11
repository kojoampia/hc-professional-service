package net.jojoaddison.service;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * A call to patientservice did not happen. Not "the collection is empty", and not "the write was
 * refused".
 *
 * <p><strong>Why this type exists.</strong> {@link PatientServiceClient#getAll} used to answer an
 * empty list on every failure, which is the right shape for a failed read — half a collection is the
 * exact defect backlog item 22 fixed and carries no signal at all — but it made the decision
 * <em>once, in the client, for four callers who mean different things by an empty list</em>. Two of
 * them read it as an outage and kept their stored data; one rendered a quiet week; and
 * {@link PatientDirectoryService} read it as <em>entitlement</em> and told a clinician the patient in
 * front of them was not in their caseload. This exception carries the distinction out to each caller
 * so that every one of them decides for itself. See {@code docs/backlog.md} item 24.
 *
 * <p><strong>503, not 500.</strong> The annotation is what {@code ExceptionTranslator} resolves
 * through {@code resolveResponseStatus}, so an uncaught one becomes an RFC 7807 problem with status
 * {@code 503}. That is both truthful and operationally different from a 404: it is the difference
 * between "call the office about your caseload" and "wait five minutes".
 *
 * <p><strong>Except that "wait five minutes" is wrong advice for half of these, which is what
 * {@link Fault} is for.</strong> The client's outermost {@code catch} is deliberately broad — a call
 * that failed for a reason nobody anticipated is still a call that failed, and answering empty for it
 * is the conflation this type exists to remove — but breadth means a <em>schema</em> fault arrives
 * here too. Jackson throws on a field the sibling has changed the type of, and the whole clinician
 * surface then answers 503 <em>persistently</em>, until a DTO or the sibling changes; that drift has
 * happened once already (see {@code PatientServiceDtos.ActivityLog}, corrected 2026-08-22). Both are
 * 503 on purpose — showing a clinician an empty record is worse than showing them an error either
 * way — but an operator must be able to tell "the sibling is down" from "this service cannot read
 * the sibling", because only one of the two clears itself. So every one of these names its fault, in
 * the message and therefore in the problem detail, and the client logs the unrecoverable ones at
 * ERROR and the rest at WARN.
 *
 * <p><strong>It deliberately carries no cause.</strong> {@code ExceptionTranslator} builds the
 * problem {@code detail} from {@code err.getCause().getMessage()} in preference to the exception's
 * own, and the cause here is a {@code RestClient} failure whose message quotes the sibling's internal
 * base URL — {@code http://hc-patient-service:8081/...}. That is not something to put on a
 * clinician's screen, and the {@code prod} profile's {@code containsPackageName} scrub does not catch
 * it: none of the package prefixes it looks for appears in {@code "hc-patient-service: Name or
 * service not known"}. The underlying throwable is logged with its stack trace at the point of
 * failure instead, which is where an operator looks for it.
 */
@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE, reason = "The patient service could not be reached")
public class PatientServiceUnavailableException extends RuntimeException {

    /**
     * Why the call did not happen — and, above all, whether waiting is the remedy.
     *
     * <p>Every value is a 503 to the caller. The distinction is for whoever is paged: {@link #SCHEMA},
     * {@link #NO_TOKEN} and {@link #UPSTREAM_FORBIDDEN} do not clear on their own, while the rest are
     * the sibling or the network being temporarily unhappy.
     *
     * <p><strong>The three that do not clear are not three of a kind, which is why
     * {@link #isAuthorisationRefusal()} exists beside {@link #clearsOnRetry()}</strong> (backlog item
     * 107). {@code SCHEMA} and {@code NO_TOKEN} want a change to <em>this</em> service before the read
     * can ever work; {@code UPSTREAM_FORBIDDEN} wants no change at all, because nothing is broken — the
     * sibling read the request, understood it, and refused this caller's discipline. Reporting it the
     * way the other two are reported would page somebody about a rule working as designed.
     */
    public enum Fault {
        /** The request never got an answer: DNS, connection refused, a read timeout on one page. */
        TRANSPORT("the sibling could not be reached", true),
        /** The sibling answered, with a status this client cannot use. */
        UPSTREAM_STATUS("the sibling answered an error status", true),
        /**
         * The sibling answered <b>403</b>: its scope of practice does not admit this caller's role.
         *
         * <p><b>Split out of {@link #UPSTREAM_STATUS} by backlog item 107</b>, which found it live:
         * {@code GET /api/patients} answered 503 for a pharmacist, a chemist and a technician with the
         * body <em>"UPSTREAM_STATUS - the sibling answered an error status; may clear on retry
         * [Forbidden]"</em>. Every clause of that was misleading. hc-patient owns the scope-of-practice
         * matrix and those three disciplines have no scope over its activity-log domain, so the 403 is
         * correct, deliberate, permanent, and identical on every retry for ever. An operator handed
         * <em>"may clear on retry"</em> retries, then checks the network, then the sibling's health —
         * three places, none of them the answer.
         *
         * <p><b>401 is deliberately not this.</b> It does not mean the caller's role was refused — this
         * service already authenticated them — it means the token this service <em>relayed</em> was not
         * accepted over there, which in practice is the shared signing key having drifted between the
         * stacks. That is an estate fault and stays {@link #UPSTREAM_STATUS}; see
         * {@code PatientServiceClient.failedWrite}, which has treated 401 as the exception to its own
         * pass-through rule since 2026-09-07 for the same reason.
         */
        UPSTREAM_FORBIDDEN("the sibling's scope of practice does not admit this caller's role", false),
        /** The whole-collection wall-clock deadline ran out mid-read. */
        BUDGET_EXHAUSTED("the read budget was exhausted", true),
        /** The runaway page guard tripped: the collection is larger than this client will read. */
        PAGE_GUARD("the page guard was exceeded", true),
        /**
         * The sibling's JSON did not fit this service's DTOs.
         *
         * <p>The one fault here that is a defect in <em>this</em> repository rather than a condition
         * of the estate, and the one that persists: every subsequent read fails identically until a
         * DTO in {@code PatientServiceDtos} or the sibling's schema changes.
         */
        SCHEMA("the sibling's response did not fit this service's DTOs", false),
        /** There was no caller token to relay, so the request was never made. */
        NO_TOKEN("there was no caller token to relay", false),
        /** Something else entirely. Worth reading the log line: the class name is in the message. */
        UNKNOWN("the call failed for a reason this client does not recognise", true);

        private final String description;
        private final boolean clearsOnRetry;

        Fault(String description, boolean clearsOnRetry) {
            this.description = description;
            this.clearsOnRetry = clearsOnRetry;
        }

        public String description() {
            return description;
        }

        /** Whether waiting is a plausible remedy. False means a change is needed before it can work. */
        public boolean clearsOnRetry() {
            return clearsOnRetry;
        }

        /**
         * Whether the sibling <em>refused</em> this caller rather than failing to answer them.
         *
         * <p>Two callers ask. {@code PatientServiceClient.failedRead} and {@code failedWrite} ask so
         * that a rule working as designed is not logged as a fault of anybody's; and
         * {@code PatientDirectoryService} asks so that a part of a composed read the caller may never
         * see can be reported as not-permitted instead of taking the whole read down with it. Both
         * want <em>this</em> question and not {@link #clearsOnRetry()}, which a refusal shares with
         * {@link #SCHEMA} while meaning something else entirely.
         *
         * <p>A predicate rather than an equality test at each site, so that a second refusal-shaped
         * status can join it here rather than in two {@code if}s and a service.
         */
        public boolean isAuthorisationRefusal() {
            return this == UPSTREAM_FORBIDDEN;
        }

        /**
         * What a throwable from {@code RestClient} actually was.
         *
         * <p>Walks the cause chain rather than switching on the top type, because the one that
         * matters is not visible there: a Jackson mapping failure surfaces as a plain
         * {@code RestClientException} — the wrapper {@code HttpMessageConverterExtractor} puts around
         * it — with an {@code HttpMessageNotReadableException} underneath. Reading only the top type
         * classifies schema drift as {@link #UNKNOWN}, which is exactly the answer that made a
         * one-row schema change indistinguishable from a sibling outage.
         *
         * <p>The depth cap is not paranoia about this client's own exceptions; it is what makes a
         * self-referencing cause chain from any library below impossible to hang on.
         *
         * <p><b>An answered status is read for which status it was</b> (backlog item 107). Every one
         * of them classified as {@link #UPSTREAM_STATUS} until 2026-09-11, so a 403 — a permanent
         * decision about who is asking — carried the same retryability as a 502. The one status split
         * out is 403, deliberately: it is the only one that means <em>the sibling understood the
         * request and will not serve this caller</em>. A 400 or a 404 is also unlikely to clear on its
         * own, but both mean this client is asking wrongly, which is a defect to find rather than a
         * rule to report, and neither has been observed here.
         */
        public static Fault of(Throwable failure) {
            Throwable current = failure;
            for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
                if (current instanceof HttpMessageConversionException) {
                    return SCHEMA;
                }
                if (current instanceof ResourceAccessException) {
                    return TRANSPORT;
                }
                if (current instanceof RestClientResponseException answered) {
                    return HttpStatus.FORBIDDEN.isSameCodeAs(answered.getStatusCode()) ? UPSTREAM_FORBIDDEN : UPSTREAM_STATUS;
                }
                if (current instanceof IOException) {
                    return TRANSPORT;
                }
                current = current.getCause();
            }
            return UNKNOWN;
        }
    }

    /** Deep enough for any wrapping the HTTP stack does, shallow enough to end on a cyclic chain. */
    private static final int MAX_CAUSE_DEPTH = 10;

    private final String path;
    private final Fault fault;

    private PatientServiceUnavailableException(String verb, String path, Fault fault, String detail) {
        super(message(verb, path, fault, detail));
        this.path = path;
        this.fault = fault;
    }

    /**
     * A collection that could not be read.
     *
     * @param path the sibling collection, e.g. {@code /api/clinical-cases}. A path of the sibling's
     *     API, not of this request — safe to surface, and the one detail that makes two of these
     *     distinguishable in a log.
     * @param fault why the read did not complete
     * @param detail the client's own words, or the failure's class name. <b>Never a message from the
     *     transport</b>: those quote the base URL they called.
     */
    public static PatientServiceUnavailableException read(String path, Fault fault, String detail) {
        return new PatientServiceUnavailableException("read", path, fault, detail);
    }

    /**
     * A write that could not be delivered. Same rules as {@link #read}.
     *
     * <p>Writes raised the raw {@code RestClientException} until 2026-09-07, which put
     * {@code hc-patient-service: Name or service not known} into a public error body — the very leak
     * this type's no-cause rule exists to prevent, live on the path item 24's own table claimed was
     * already covered. A <em>refusal</em> by the sibling is not this: see
     * {@code PatientServiceClient.failedWrite}, which passes a 4xx through with its status intact.
     */
    public static PatientServiceUnavailableException write(String path, Fault fault, String detail) {
        return new PatientServiceUnavailableException("written to", path, fault, detail);
    }

    private static String message(String verb, String path, Fault fault, String detail) {
        return (
            "patientservice could not be " +
            verb +
            " (" +
            path +
            "): " +
            fault +
            " - " +
            fault.description() +
            "; " +
            (fault.clearsOnRetry() ? "may clear on retry" : "will NOT clear on retry") +
            (detail == null || detail.isBlank() ? "" : " [" + detail + "]")
        );
    }

    /** The sibling path that could not be reached. */
    public String path() {
        return path;
    }

    /** What went wrong, classified. */
    public Fault fault() {
        return fault;
    }
}
