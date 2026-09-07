package net.jojoaddison.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A read of one of patientservice's collections did not happen. Not "the collection is empty".
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
 * <p><strong>It deliberately carries no cause.</strong> {@code ExceptionTranslator} builds the
 * problem {@code detail} from {@code err.getCause().getMessage()} in preference to the exception's
 * own, and the cause here is a {@code RestClient} failure whose message quotes the sibling's internal
 * base URL — {@code http://hc-patient-service:8081/...}. That is not something to put on a
 * clinician's screen. The underlying throwable is logged with its stack trace at the point of
 * failure instead, which is where an operator looks for it.
 */
@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE, reason = "The patient service could not be read")
public class PatientServiceUnavailableException extends RuntimeException {

    private final String path;

    /**
     * @param path the sibling collection that could not be read, e.g. {@code /api/clinical-cases}.
     *     A path of the sibling's API, not of this request — safe to surface, and the one detail
     *     that makes two of these distinguishable in a log.
     * @param reason why the read did not complete, in the client's own words
     */
    public PatientServiceUnavailableException(String path, String reason) {
        super("patientservice could not be read (" + path + "): " + reason);
        this.path = path;
    }

    /** The sibling collection that could not be read. */
    public String path() {
        return path;
    }
}
