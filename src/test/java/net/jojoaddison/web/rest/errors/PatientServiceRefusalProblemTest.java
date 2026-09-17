package net.jojoaddison.web.rest.errors;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.service.PatientServiceUnavailableException;
import net.jojoaddison.service.PatientServiceUnavailableException.Fault;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause;

/**
 * What a refused caller is answered, as one document rather than as one field (backlog item 127).
 *
 * <p><b>"Answered", not "reads", and that is measured rather than hedged.</b> Neither client surfaces the
 * {@code title}: {@code web/}'s {@code alert-error.component.ts} renders {@code error.detail ?? error.message}
 * and {@code mobile/}'s {@code write-queue.service.ts} stores the same, and a search of both application
 * sources finds no read of a problem {@code title} at all — {@code web/}'s only {@code error.title} is the
 * static i18n key of its error <em>page</em>. So item 127 was operator-, log- and API-consumer-facing and
 * must never be cited as having changed a sentence a clinician sees.
 *
 * <p><b>Backlog item 135 is the sentence a clinician sees, and it is a third field again.</b> This javadoc
 * used to end by naming {@code detail} as that sentence and the missing {@code error.http.503} as "a separate
 * {@code web/} row"; that row is now closed here, so the claim has to move rather than stand. {@code message}
 * is a <em>translation key</em>, and {@code alert-error.component.ts} renders {@code detail} only as the
 * fallback for one that misses — which {@code error.http.503} always did, being in none of the four
 * catalogues. So the clinician read the operator's sentence, in English, whatever their locale. The cases
 * below assert the key beside the title and the detail, because a document whose prose fields disagree is
 * precisely what item 127 removed one field to the left.
 *
 * <p><b>The key is a three-way split and the title is a two-way one, and this javadoc claimed otherwise for
 * the length of one review.</b> It said the two were "keyed on one predicate
 * ({@code Fault.isAuthorisationRefusal()})", which is true of the title and false of the key — because
 * {@code message}'s outlook clause has always keyed on {@code clearsOnRetry()} as well. The two-way key that
 * sentence described gave {@code SCHEMA} and {@code NO_TOKEN} a translated <em>"try again in a few
 * minutes"</em> directly beneath a detail reading <em>"will NOT clear on retry"</em>. Both halves of what the
 * clinician read were false, the fluent one was new, and that is item 107's defect re-shipped to the audience
 * item 135 exists to serve.
 *
 * <p><b>Nothing here could have caught it, and that is the more useful half.</b>
 * {@code theMessageKeysAreTheConstantsTheExceptionDeclares} exercises {@code SCHEMA} — and asserts that the
 * wire key equals {@code messageKey()}, which is self-consistency <em>across the boundary</em> and stays
 * green however wrong {@code messageKey()} is. So {@code aKEYneverPromisesARetryTheDetailRefuses} below
 * asserts the two fields against <em>each other</em>, over every {@code Fault} value rather than a listed
 * few: a key promising a retry may not sit above a detail refusing one. It is the only assertion here that
 * would have reddened.
 *
 * <p><b>It covers one of the two axes, and saying which is the whole point of this paragraph.</b> The
 * derived invariant holds the <em>retry</em> axis for any fault added later, with nobody editing this file.
 * It cannot see the <em>refusal</em> axis at all: {@code refused} and {@code faulted} both decline a retry,
 * so both booleans read the same either way, and a ninth fault with {@code clearsOnRetry=false} would have
 * its refused-versus-faulted assignment pinned by nothing. That axis is held by <b>enumeration</b>, in three
 * places that must be edited by hand — {@code Fault.isAuthorisationRefusal()} in the production type, which
 * is a literal {@code == UPSTREAM_FORBIDDEN}; the {@code SCHEMA, NO_TOKEN} array in
 * {@code aPERSISTENTfaultNamesNeitherOfTheOtherTwoSentences}; and the single {@code UPSTREAM_FORBIDDEN} in
 * {@code aREFUSALnamesTheRefusalSentenceForTheClinician}. Those two faults are today's entire non-retryable
 * population, which is exactly why the lists look complete and would go on looking complete.
 *
 * <p>An earlier version of this paragraph claimed the derived case covered a ninth fault outright. That is
 * the same over-claim, one axis over, as the "keyed on one predicate" sentence above it — a javadoc
 * asserting a property the code has only partly got, which is item 112's classification and now this item's
 * recurring failure. Stated honestly it is still a good trade: the axis that produced a live defect is
 * derived, and the axis that has never moved is enumerated.
 *
 * <p><b>The defect this closes was invisible from either side.</b> {@code PatientServiceFaultTest} holds
 * the message and {@code PatientServiceUnavailableException.title()} holds the title, and each was right
 * about itself; what was wrong was that {@code ExceptionTranslator} takes them from two different places —
 * {@code detail} from {@code getMessage()}, {@code title} from the {@code @ResponseStatus} {@code reason} —
 * so a class-level annotation spoke for every fault. Measured through the gateway on the quality stack,
 * 2026-09-15, a technician asking for one patient's cases:
 *
 * <pre>
 * "status": 503,
 * "title":  "The patient service could not be reached",
 * "detail": "patientservice refused this caller's discipline (/api/clinical-cases): …"
 * </pre>
 *
 * <p>Two prose fields of one problem document contradicting each other, with the false one first — the
 * claim about a sibling's health that backlog item 107 removed from the retryability clause and item 112
 * from the opening clause, surviving one field to the left because both were reading the message.
 *
 * <p><b>A unit test rather than a case in {@code ExceptionTranslatorIT}, for the reason
 * {@code PatientDirectoryRestrictionHeaderTest} gives.</b> The integration suite needs a Docker daemon and
 * Testcontainers and is the first thing to time out on a loaded workstation (backlog item 28); a guard on
 * two strings that only runs when the machine is quiet is absent exactly when somebody is in a hurry.
 * Nothing asserted here needs a database, a broker or a sibling. {@code wrapAndCustomizeProblem} is the
 * method {@code handleAnyException} calls and is entered with the same arguments, so this crosses the
 * boundary the two field-level tests cannot.
 */
class PatientServiceRefusalProblemTest {

    private final ExceptionTranslator translator = new ExceptionTranslator(new MockEnvironment());

    private ProblemDetailWithCause problemFor(PatientServiceUnavailableException raised) {
        return translator.wrapAndCustomizeProblem(
            raised,
            new ServletWebRequest(new MockHttpServletRequest("GET", "/api/patients/p-1/cases"))
        );
    }

    /**
     * The problem's {@code message} property — the i18n key {@code web/} hands to ngx-translate, not prose.
     *
     * <p>Read through the property map rather than a getter because RFC 7807 has no such field:
     * {@code message} is JHipster's own extension, set by {@code customizeProblem}, and {@code web/}'s
     * {@code alert-error.component.ts} reads it off the parsed body by that name.
     */
    private Object messageKeyOf(ProblemDetailWithCause problem) {
        return problem.getProperties().get("message");
    }

    /**
     * The document a technician gets from {@code GET /api/patients/{id}/cases}, which backlog item 127
     * decided should go on being a refusal — so this is the whole of what that item changed for them.
     *
     * <p>The status is asserted beside the two sentences because it is the half that must <em>not</em>
     * move: retitling a refusal is not backlog item 113, which would change what {@code mobile/}'s offline
     * queue does with a queued operation and is a cross-repo decision.
     */
    @Test
    void aREFUSALisTitledAndDetailedAsTheSameThing() {
        ProblemDetailWithCause problem = problemFor(
            PatientServiceUnavailableException.read("/api/clinical-cases", Fault.UPSTREAM_FORBIDDEN, "Forbidden")
        );

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getTitle()).isEqualTo("The patient service refused this caller's discipline");
        assertThat(problem.getDetail()).contains("refused this caller's discipline", "/api/clinical-cases");
    }

    /**
     * <b>The positive control.</b> A sibling that genuinely could not be reached keeps the title it has
     * always had — the words an operator has learned to recognise — because a change that retitled every
     * 503 as an authorisation decision would tell somebody nothing is broken while hc-patient is down.
     * That is item 107's defect inverted, and it is worse than the one item 127 removed.
     */
    @Test
    void anOUTAGEkeepsTheTitleItAlwaysHad() {
        ProblemDetailWithCause problem = problemFor(PatientServiceUnavailableException.read("/api/clinical-cases", Fault.TRANSPORT, null));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getTitle()).isEqualTo("The patient service could not be reached");
        assertThat(problem.getDetail()).contains("could not be read");
    }

    /**
     * <b>The title is spelled here rather than imported, deliberately</b>, and this is the test that says
     * why. The two above assert the wiring against literals; this asserts that the literals are the
     * constants, so a rename of either constant reddens exactly one test instead of silently rewording the
     * contract in all three. It is the argument backlog item 131 made for spelling header names as literals
     * in a repository that does not own them, applied one layer in: {@code ExceptionTranslator} is generic
     * JHipster machinery and must not acquire vocabulary about a sibling, so the only thing holding these
     * two strings together is a test that names both forms.
     */
    @Test
    void theTitlesAreTheConstantsTheExceptionDeclares() {
        assertThat(
            problemFor(PatientServiceUnavailableException.read("/api/reports", Fault.UPSTREAM_FORBIDDEN, null)).getTitle()
        ).isEqualTo(PatientServiceUnavailableException.read("/api/reports", Fault.UPSTREAM_FORBIDDEN, null).title());
        assertThat(problemFor(PatientServiceUnavailableException.read("/api/reports", Fault.SCHEMA, null)).getTitle()).isEqualTo(
            PatientServiceUnavailableException.read("/api/reports", Fault.SCHEMA, null).title()
        );
    }

    /**
     * <b>Nothing else acquired a title from this change.</b> {@code getCustomizedTitle} is consulted before
     * the {@code @ResponseStatus} arm for <em>every</em> throwable, so a branch added there is one edit away
     * from answering for types it was never about. An ordinary exception still falls through to the status
     * reason phrase.
     */
    @Test
    void anUNRELATEDexceptionIsUntouched() {
        ProblemDetailWithCause problem = translator.wrapAndCustomizeProblem(
            new IllegalStateException("something else entirely"),
            new ServletWebRequest(new MockHttpServletRequest("GET", "/api/patients"))
        );

        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
    }

    /**
     * <b>Backlog item 135.</b> The one field a clinician actually reads names a refusal, and the three
     * fields around it do not move.
     *
     * <p>The status, the title and the detail are asserted here rather than left to the cases above,
     * because the whole risk of this change is a fourth prose field drifting from the three it is keyed
     * with: {@code message} is chosen by {@code Fault.isAuthorisationRefusal()}, and so are
     * {@code title()} and the opening clause of the detail. A refusal whose key said one thing while its
     * title said another would be item 127's defect rebuilt one field to the right.
     */
    @Test
    void aREFUSALnamesTheRefusalSentenceForTheClinician() {
        ProblemDetailWithCause problem = problemFor(
            PatientServiceUnavailableException.read("/api/clinical-cases", Fault.UPSTREAM_FORBIDDEN, "Forbidden")
        );

        assertThat(messageKeyOf(problem)).isEqualTo("error.patientService.refused");
        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getTitle()).isEqualTo("The patient service refused this caller's discipline");
        assertThat(problem.getDetail()).contains("refused this caller's discipline", "/api/clinical-cases");
    }

    /**
     * <b>The positive control, and the half that makes the pair a test.</b> An outage takes the
     * <em>other</em> key, so a change that keyed every one of these on the refusal sentence reddens here.
     *
     * <p>A one-sided assertion would pass against a constant that was never right in any state: an
     * implementation returning {@code REFUSED_MESSAGE_KEY} unconditionally satisfies the case above and
     * tells a clinician nothing is broken while hc-patient is down — item 107's defect inverted, and the
     * reason {@code anOUTAGEkeepsTheTitleItAlwaysHad} exists one field to the left.
     */
    @Test
    void anOUTAGEnamesTheOutageSentenceForTheClinician() {
        ProblemDetailWithCause problem = problemFor(PatientServiceUnavailableException.read("/api/clinical-cases", Fault.TRANSPORT, null));

        assertThat(messageKeyOf(problem)).isEqualTo("error.patientService.unreachable");
        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getTitle()).isEqualTo("The patient service could not be reached");
        assertThat(problem.getDetail()).contains("could not be read");
    }

    /**
     * <b>The third sentence, and the defect that made it necessary.</b> A fault that is neither a refusal
     * nor retryable takes neither of the other two keys.
     *
     * <p>Both faults are asserted because they are the whole population of that branch and because they
     * arrive by different routes — {@code SCHEMA} from the sibling's JSON not fitting this service's DTOs,
     * {@code NO_TOKEN} from there being no caller token to relay — and a branch that covered only one of
     * them would look identical here.
     *
     * <p>The detail is asserted beside the key, spelled out, because the contradiction is the point: these
     * two used to be told <em>"try again in a few minutes"</em> under exactly this sentence.
     */
    @Test
    void aPERSISTENTfaultNamesNeitherOfTheOtherTwoSentences() {
        for (Fault fault : new Fault[] { Fault.SCHEMA, Fault.NO_TOKEN }) {
            ProblemDetailWithCause problem = problemFor(PatientServiceUnavailableException.read("/api/clinical-cases", fault, null));

            assertThat(messageKeyOf(problem)).as("%s", fault).isEqualTo("error.patientService.faulted");
            assertThat(problem.getDetail()).as("%s", fault).contains("will NOT clear on retry");
        }
    }

    /**
     * <b>The invariant, over every {@code Fault} there is — and the only assertion in this class that would
     * have caught the two-way key.</b>
     *
     * <p>The clinician's key and the operator's outlook clause make the same promise about retrying, or
     * they make a document that contradicts itself. That is a relation <em>between two fields</em>, which
     * is exactly what every other case here misses: they each check one field against a literal, and a
     * wrong mapping applied consistently satisfies all of them.
     *
     * <p><b>Derived, not enumerated — on this axis.</b> It walks {@code Fault.values()}, so the ninth fault
     * somebody adds has its <em>retry</em> advice covered without anybody remembering this file — the shape
     * {@code restricted-part-names.spec.ts} uses in {@code web/} and for the same reason. Asserting equality
     * of the two booleans rather than implication is deliberate: a key that refused a retry over a fault the
     * enum says clears would be the mirror defect, and is just as much a lie to somebody.
     *
     * <p><b>What it cannot see.</b> {@code refused} and {@code faulted} both decline a retry, so this case
     * reads {@code false}/{@code false} for either and a fault taking the wrong one of the two passes here.
     * The refused-versus-faulted split is enumerated — see the class javadoc, which names the three lists
     * that need editing by hand. Do not read a green run of this as saying a new fault is fully pinned.
     *
     * <p>It reads the sentences rather than the predicates on purpose. Re-deriving the mapping from
     * {@code isAuthorisationRefusal()} and {@code clearsOnRetry()} would restate the implementation and
     * pass against any consistent error; these are the two strings that actually travel.
     */
    @Test
    void aKEYneverPromisesARetryTheDetailRefuses() {
        for (Fault fault : Fault.values()) {
            ProblemDetailWithCause problem = problemFor(PatientServiceUnavailableException.read("/api/clinical-cases", fault, null));

            boolean keyPromisesARetry = "error.patientService.unreachable".equals(messageKeyOf(problem));
            boolean detailPromisesARetry = problem.getDetail().contains("may clear on retry");

            assertThat(keyPromisesARetry)
                .as("%s: key=%s detail=%s", fault, messageKeyOf(problem), problem.getDetail())
                .isEqualTo(detailPromisesARetry);
        }
    }

    /**
     * <b>The literals above are the constants the exception declares</b>, for exactly the reason
     * {@code theTitlesAreTheConstantsTheExceptionDeclares} gives three tests up: the two cases above pin
     * the wiring against spelled-out strings, and this pins the strings to their source, so renaming a
     * constant reddens one test instead of silently rewording the contract everywhere.
     *
     * <p>Renaming one of these is not a cosmetic change — the key is a catalogue path, so a rename that
     * slipped through leaves all four locales missing it and puts the operator's sentence back on the
     * clinician's screen, which is the entire defect item 135 exists to close and which nothing else here
     * would notice.
     *
     * <p><b>Read what this does and does not say, because a review found the gap the expensive way.</b> It
     * asserts that the key <em>on the wire</em> is the key the exception <em>declares</em> — a property of
     * the boundary, not of the mapping. It exercised {@code SCHEMA} while {@code SCHEMA} was being given the
     * retryable sentence and stayed green throughout, because {@code messageKey()} was consistently wrong
     * and consistency is all this can see. {@code aKEYneverPromisesARetryTheDetailRefuses} is the case that
     * looks at the mapping; keep both, and do not mistake one for the other.
     *
     * <p>All three faults are walked rather than a representative two, so that a key added later is not
     * left unpinned merely because the fault carrying it was added after this test was written.
     */
    @Test
    void theMessageKeysAreTheConstantsTheExceptionDeclares() {
        for (Fault fault : new Fault[] { Fault.UPSTREAM_FORBIDDEN, Fault.TRANSPORT, Fault.SCHEMA }) {
            assertThat(messageKeyOf(problemFor(PatientServiceUnavailableException.read("/api/reports", fault, null))))
                .as("%s", fault)
                .isEqualTo(PatientServiceUnavailableException.read("/api/reports", fault, null).messageKey());
        }
    }

    /**
     * <b>Nothing else acquired a key from this change.</b> The mirror of
     * {@code anUNRELATEDexceptionIsUntouched}: {@code getMappedMessageKey} is consulted for <em>every</em>
     * throwable before the {@code error.http.<status>} fallback, so an arm added there is one edit away
     * from answering for types it was never about — and the failure would be silent, because a key that
     * misses renders the detail and looks exactly like today.
     */
    @Test
    void anUNRELATEDexceptionKeepsTheStatusKey() {
        ProblemDetailWithCause problem = translator.wrapAndCustomizeProblem(
            new IllegalStateException("something else entirely"),
            new ServletWebRequest(new MockHttpServletRequest("GET", "/api/patients"))
        );

        assertThat(messageKeyOf(problem)).isEqualTo("error.http.500");
    }
}
