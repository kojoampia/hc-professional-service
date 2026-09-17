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
@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE, reason = PatientServiceUnavailableException.UNREACHABLE_TITLE)
public class PatientServiceUnavailableException extends RuntimeException {

    /**
     * The problem-detail title for a read that genuinely did not happen — and, until backlog item 127,
     * for every one of these, a refusal included.
     *
     * <p><b>Named rather than spelled twice.</b> It is the {@code reason} of the annotation above
     * <em>and</em> the non-refusal arm of {@link #title()}; two literals meaning the same thing is how a
     * reworded annotation would leave {@link #title()} serving the old sentence to the one caller it is
     * not about.
     */
    static final String UNREACHABLE_TITLE = "The patient service could not be reached";

    /**
     * The problem-detail title for a refusal (backlog item 127).
     *
     * <p>Deliberately the opening clause of {@link #message}'s refusal branch, in the title's voice, so
     * that the two prose fields of one problem document say the same thing. They said opposite things
     * until this item — see {@link #title()}.
     */
    static final String REFUSED_TITLE = "The patient service refused this caller's discipline";

    /**
     * The i18n key naming a refusal, for the clinician's sentence rather than the operator's (backlog
     * item 135).
     *
     * <p><b>This type owns the key because it owns the distinction.</b> The problem document's
     * {@code message} property is a <em>translation key</em>, not prose: {@code web/}'s
     * {@code alert-error.component.ts} hands it to ngx-translate and falls back to the {@code detail}
     * only when the key misses. Every one of these carried {@code error.http.503} — absent from all four
     * catalogues — so the key always missed and the clinician read {@link #message}, which is written
     * for an operator, in English, whatever their locale. Items 107, 112 and 127 each improved that
     * sentence for the reader who was not looking at it.
     *
     * <p>Named on the exception and not in {@code ErrorConstants} for the reason {@link #title()} gives
     * for living here: {@code ExceptionTranslator} is generic JHipster machinery and keeps no vocabulary
     * of its own about the sibling. {@code TechnicalStructureTest} enforces the same direction from the
     * other side — {@code ..service..} may not reference {@code ..web..}, so this type could not reach
     * {@code ErrorConstants} even if it wanted to.
     */
    static final String REFUSED_MESSAGE_KEY = "error.patientService.refused";

    /**
     * The i18n key for a read that did not happen and may well happen next time. See
     * {@link #REFUSED_MESSAGE_KEY}.
     *
     * <p>More than one key, for the reason item 127 gave for two titles: a clinician told "nothing is
     * broken, speak to your administrator" while hc-patient is down has been sent to the wrong person,
     * and one told "try again in a few minutes" over a scope-of-practice rule will retry for ever.
     *
     * <p><b>This one promises a retry, so it is the one that must never cover a fault that will not
     * clear</b> — which is what {@link #FAULTED_MESSAGE_KEY} exists to stop.
     */
    static final String UNREACHABLE_MESSAGE_KEY = "error.patientService.unreachable";

    /**
     * The i18n key for a fault that is nobody's rule and will not clear on its own.
     *
     * <p><b>The third key, and the one this item shipped without at first.</b> {@link #messageKey()}
     * keyed on {@link Fault#isAuthorisationRefusal()} alone, while {@link #message}'s outlook clause has
     * always keyed on <em>both</em> predicates — so {@link Fault#SCHEMA} and {@link Fault#NO_TOKEN}, the
     * two faults that are neither a refusal nor retryable, took the retryable sentence. The clinician was
     * told to try again in a few minutes directly beneath a {@code detail} reading <em>"will NOT clear on
     * retry"</em>, and both halves of what they read were false: the sibling <em>answered</em> — in a
     * shape the DTOs reject — and every retry fails identically until a DTO ships.
     *
     * <p><b>That is item 107's defect re-shipped to the other audience, which is why the third key is not
     * a refinement.</b> Item 107 exists because <em>"may clear on retry"</em> over a permanent fault sent
     * an operator to three wrong places. Before this key a {@code SCHEMA} clinician read the true, opaque,
     * untranslated operator sentence; with two keys they read a false, fluent, translated retry
     * instruction. Confidently wrong is worse than merely opaque, and {@code SCHEMA} is the one fault here
     * with a production precedent — see this class's own note on {@code PatientServiceDtos.ActivityLog},
     * corrected 2026-08-22.
     *
     * <p><b>It says a third thing, not a softer version of the other two.</b> A refusal is about who is
     * asking and will never change; an outage clears itself; this is a defect somebody must fix, so the
     * sentence says that nothing the clinician did caused it, that retrying will not help, and that the
     * administrator has the details — the only one of the three that points at a person who can act.
     */
    static final String FAULTED_MESSAGE_KEY = "error.patientService.faulted";

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

    /**
     * The sentence an operator reads, and — for a refusal — the sentence that is still not true.
     *
     * <p><b>Backlog item 112 moves the opening clause and the tail, and leaves the middle alone.</b>
     * Item 107 fixed the retryability of a refusal and left the rest of this sentence as it was, so a
     * technician opening a patient record was told <em>"patientservice could not be read
     * (/api/clinical-cases)"</em>. It <em>was</em> read: hc-patient received the request, understood it,
     * and answered — the answer was no. "Could not be read" is a claim about a service's health, and it
     * is the claim that sends an operator to the sibling's logs, its container and its network before
     * anybody thinks to look at a scope-of-practice matrix. It is the same class of untrue sentence item
     * 107 removed from the clause beside it, one clause to the left.
     *
     * <p><b>The tail moves for a reason that only shows up in company.</b> {@code will NOT clear on
     * retry} is shared with {@link Fault#SCHEMA} and {@link Fault#NO_TOKEN}, where it means <em>somebody
     * must change this service before this can ever work</em>. For a refusal it means the opposite:
     * nothing needs changing and nothing is broken. The words item 107 chose are kept verbatim — they
     * are what {@code PatientServiceFaultTest} pins and what an operator now recognises — and the
     * distinction is appended rather than substituted.
     *
     * <p><b>A refusal is only ever a read here</b>, so the branch does not have to render {@code verb}:
     * {@code PatientServiceClient.failedWrite} passes a 4xx from a write through with its own status
     * intact and never reaches this type. If that ever changes, the path in the message still says which
     * collection it was.
     */
    private static String message(String verb, String path, Fault fault, String detail) {
        String opening = fault.isAuthorisationRefusal()
            ? "patientservice refused this caller's discipline (" + path + ")"
            : "patientservice could not be " + verb + " (" + path + ")";
        String outlook = fault.isAuthorisationRefusal()
            ? "will NOT clear on retry, because nothing is broken"
            : (fault.clearsOnRetry() ? "may clear on retry" : "will NOT clear on retry");
        return (
            opening +
            ": " +
            fault +
            " - " +
            fault.description() +
            "; " +
            outlook +
            (detail == null || detail.isBlank() ? "" : " [" + detail + "]")
        );
    }

    /**
     * The RFC 7807 {@code title} that goes above {@link #getMessage()} — <b>the last clause of this
     * sentence that was still false for a refusal</b> (backlog item 127).
     *
     * <p><b>Item 127 asked whether {@code GET /api/patients/&#123;id&#125;/cases} should go on refusing a
     * technician. It should</b>, for the reason {@code PatientDirectoryService.casesFor} sets out: that
     * response <em>is</em> the refused collection and there is no honest partial of it. So what was left
     * to fix was the sentence, and the sentence had a part nobody had looked at.
     *
     * <p><b>Items 107 and 112 fixed the message and the problem document has two prose fields.</b>
     * {@code ExceptionTranslator} builds {@code detail} from {@link #getMessage()} — which since item 112
     * opens <em>"patientservice refused this caller's discipline"</em> — and builds {@code title} from the
     * {@code reason} of the {@code @ResponseStatus} above, which is a class-level annotation and so was
     * the same string for every fault. Measured on the quality stack, 2026-09-15, a technician asking for
     * one patient's cases:
     *
     * <pre>
     * "title":  "The patient service could not be reached"
     * "detail": "patientservice refused this caller's discipline (/api/clinical-cases): …"
     * </pre>
     *
     * <p>The two contradict each other, and the false one is the one a generic problem-detail renderer
     * shows first. It is the same claim about a sibling's health that item 107 removed from the
     * retryability clause and item 112 from the opening clause — surviving one field to the left, because
     * both items were reading the message and the title is not part of it.
     *
     * <p><b>Why here and not in the resource.</b> The title is wrong for a refusal on every path that
     * composes a patientservice read, not only on the cases endpoint; fixing it where item 127 found it
     * would have been the first of several copies. It is rendered per fault for the same reason
     * {@link #message} is — {@code clearsOnRetry()} and {@link Fault#isAuthorisationRefusal()} are already
     * the two questions this type answers, and a third field keyed on the same predicate cannot come to
     * disagree with them.
     *
     * <p><b>An annotation cannot do it, which is why a method does.</b> {@code @ResponseStatus} sits on
     * the class, so its {@code reason} is fixed at compile time for every instance — the same constraint
     * backlog item 113 records for the <em>status</em>, and the reason that item needs a second exception
     * type while this one needs only a getter. The annotation is still what supplies the 503 and is still
     * what an uncaught one resolves through; {@code ExceptionTranslator.getCustomizedTitle} simply prefers
     * this when it has one.
     */
    public String title() {
        return fault.isAuthorisationRefusal() ? REFUSED_TITLE : UNREACHABLE_TITLE;
    }

    /**
     * The i18n key that goes into the problem's {@code message} property — <b>the field a clinician
     * actually reads</b> (backlog item 135).
     *
     * <p><b>Items 107, 112 and 127 improved three clauses of {@link #message} and one title, and none of
     * them asked who reads them.</b> {@code web/}'s {@code alert-error.component.ts} renders
     * {@code error.detail ?? error.message} and passes {@code error.message} to ngx-translate as the key;
     * {@code AlertService} falls back to the detail only when the key misses. So the operator's sentence —
     * which names the collection, the fault and the retryability — is what a clinician was shown on screen,
     * in English, whatever their locale, because {@code error.http.503} is in none of the four catalogues.
     * Naming the fault here gives each of them their own sentence: the operator keeps {@code detail}
     * verbatim and the clinician gets {@code error.patientService.*} translated.
     *
     * <p><b>This is the same three-way split {@link #message}'s outlook clause makes, written once more
     * rather than keyed independently</b> — and it is three-way because a two-way version of it shipped
     * and was wrong. Keying on {@link Fault#isAuthorisationRefusal()} alone gave {@link Fault#SCHEMA} and
     * {@link Fault#NO_TOKEN} the retryable sentence while the {@code detail} above it said <em>"will NOT
     * clear on retry"</em>; see {@link #FAULTED_MESSAGE_KEY}, which argues why that is worse than the
     * untranslated sentence it replaced. The branches are deliberately written in the same order and on
     * the same two predicates as {@code outlook}, so that a change to one reads as an obvious omission in
     * the other.
     *
     * <p><b>What is actually guaranteed, stated precisely, because the loose version of this sentence is
     * what let the defect through.</b> This key and {@code outlook} draw the <em>same</em> three
     * distinctions, so a key promising a retry can never sit above a detail refusing one — that is the
     * invariant, and {@code PatientServiceRefusalProblemTest} asserts it over every {@link Fault} value
     * rather than over a listed few. {@link #title()} and {@code opening} draw only <em>one</em>
     * distinction, refusal against not, and this key agrees with them on that axis. It is finer than they
     * are on purpose: a title names the service's state for an operator, while this has to carry advice a
     * clinician will act on. It is <b>not</b> true that all four fields make one split, and the earlier
     * version of this paragraph said so.
     *
     * <p><b>The three sentences this names are the clinician's, not translations of the operator's.</b> A
     * refusal says the role is not permitted and that retrying will not help; an outage says the service is
     * not answering, that the clinician caused nothing, and to try again shortly; a fault says something is
     * broken, that retrying will not help, and that the administrator has the details. None of them names
     * the collection or the {@link Fault}: those are an operator's vocabulary and stay in {@code detail}.
     */
    public String messageKey() {
        if (fault.isAuthorisationRefusal()) {
            return REFUSED_MESSAGE_KEY;
        }
        return fault.clearsOnRetry() ? UNREACHABLE_MESSAGE_KEY : FAULTED_MESSAGE_KEY;
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
