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
 * <p><b>"Answered", not "reads", and that is measured rather than hedged.</b> Neither client surfaces this
 * field: {@code web/}'s {@code alert-error.component.ts} renders {@code error.detail ?? error.message} and
 * {@code mobile/}'s {@code write-queue.service.ts} does the same, and a search of both application sources
 * finds no read of a problem {@code title} at all — {@code web/}'s only {@code error.title} is the static
 * i18n key of its error <em>page</em>. So this fix is operator-, log- and API-consumer-facing, and item 127
 * must never be cited as having changed a sentence a clinician sees. The sentence a clinician sees is
 * {@code detail}, which items 107 and 112 fixed, rendered untranslated because {@code error.http.503} is
 * absent from all four catalogues — a separate {@code web/} row.
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
}
