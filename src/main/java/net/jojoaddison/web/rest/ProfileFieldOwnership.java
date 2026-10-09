package net.jojoaddison.web.rest;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.jojoaddison.domain.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.ObjectNode;

/**
 * One-writer-per-field on {@link Profile}: which fields a general-purpose profile write may not
 * change, and the endpoint that does set each (backlog.md item 60).
 *
 * <h2>Why this moved out of {@code ProfileResource} (profile.md, T1)</h2>
 *
 * <p>There are now <b>two</b> partial-write paths into this collection —
 * {@code PATCH /api/profiles/&#123;id&#125;}, open to all six {@code CLINICAL_MUTATION} roles, and
 * {@code PUT /api/profile}, the caller's own — and the rule has to be the same on both. A second copy
 * of the map would be the shape this repository has been burned by twice over: {@code quality/}'s
 * items 84, 91 and 92 record a fix applied to one of two correct-looking copies, and the lesson
 * written up from them is that <b>two correct-for-now copies is how an estate arrives at one wrong
 * one</b>. So there is one map, one refusal and one message, in one place neither resource owns.
 *
 * <p><b>Read the set below rather than a count.</b> Its heading in {@code ProfileResource} said "five
 * refusals and two applications" until {@code Profile.status} arrived and made it six and two, which
 * is the shape of staleness this whole area is annotated against — a number maintained by hand in
 * prose that nothing compiles.
 * {@code ProfilePatchFieldCoverageIT.everyProfileFieldIsEitherAppliedOrRefused} is what forces the
 * decision for a field added later; it is not able to force a decision about a sentence.
 *
 * <p>{@code ProfileService.partialUpdate} copied eleven of {@code Profile}'s fields and stopped, so a
 * patch naming any of the other seven answered 200 with the row unchanged and the unmodified profile
 * as the body — the caller's own read-back confirming a write that never happened. That the split was
 * eleven and seven is an accident, not a design: {@code .jhipster/Profile.json} lists {@code title},
 * {@code contacts}, {@code specialtyCategoryId} and {@code teamIds}, so the generator would have
 * emitted them; the three push preferences were added by hand for MOB9 and were never in the
 * generator's input at all. Nobody decided any of this, which is why the fix had to be a decision
 * rather than a completion.
 *
 * <p><b>{@code title} and {@code contacts} are applied</b>, alongside the rest.
 * {@code PUT /api/profile} writes both from a clinician's own onboarding body, on
 * exactly the same footing as {@code firstName}, {@code address} and {@code cardNumber} — fields
 * these endpoints have always let the same six roles change. Refusing them would expose no less data
 * and would leave no way to correct a title short of a whole-document {@code PUT}.
 *
 * <p><b>The fields below are refused, because each already has an owner with narrower authority than
 * the endpoints that consult this class.</b>
 *
 * <ul>
 *   <li>{@code specialtyCategoryId} and {@code teamIds} are set by
 *       {@code PUT /api/professional-application/&#123;id&#125;/organization}, which is
 *       {@code ROLE_ADMIN} only and appends an {@code OnboardingEvent} as part of the state machine.
 *       Copying them on the {@code PATCH} would give every nurse the power to file any colleague
 *       under any discipline, and would move an assignment that the application's own history could
 *       not then explain.</li>
 *   <li>The three push preferences are set by {@code PUT /api/notifications/preferences}, which
 *       writes the <em>caller's own</em> profile and no one else's. Copying them on the {@code PATCH}
 *       would let one clinician switch off another's compliance notifications — the nudge that says
 *       their licence is about to expire — and the victim would see nothing.</li>
 *   <li>{@code status} is the sixth, and it is the one that could not be anything else.
 *       {@code ProfileStatus} is the alphabet of {@code OnboardingService}'s server-side state
 *       machine, and every legal move between its values is a {@code ROLE_ADMIN}
 *       {@code PUT /api/professional-application/&#123;id&#125;/**} that checks
 *       {@code LEGAL_TRANSITIONS} and appends an {@code OnboardingEvent}. A merge here checks nothing
 *       and appends nothing, so copying the field would let a caller write {@code APPROVED} over
 *       {@code APPLICATION_STARTED} — jumping credential review outright — and leave an application
 *       whose own history does not contain the step that approved it.</li>
 * </ul>
 *
 * <p>⛔ <b>The refusals are not weaker on the own-profile path, and {@code status} is the case that
 * shows why.</b> "It is my own row" is an argument about <em>disclosure</em> and these six are not
 * about disclosure: {@code status} is the applicant's own field, and an applicant who could write it
 * would approve their own credential review. {@code profile.md} renders {@code profile.status} in the
 * page header, so it stays <b>readable</b> on {@code GET /api/profile} and refused on the write —
 * which is the whole of the distinction between the two.
 */
final class ProfileFieldOwnership {

    /** Field -> the endpoint that sets it. See the class note; read the set, never a count. */
    static final Map<String, String> REFUSED_FIELDS = Map.of(
        "specialtyCategoryId",
        "PUT /api/professional-application/{id}/organization",
        "teamIds",
        "PUT /api/professional-application/{id}/organization",
        "pushMessagesEnabled",
        "PUT /api/notifications/preferences",
        "pushComplianceEnabled",
        "PUT /api/notifications/preferences",
        "pushShowSenderName",
        "PUT /api/notifications/preferences",
        "status",
        "PUT /api/professional-application/{id}/decide and the transitions beside it"
    );

    private ProfileFieldOwnership() {}

    /**
     * Refuses, rather than silently ignores, a write that would change a field the calling endpoint
     * does not own (backlog.md item 60).
     *
     * <p><b>The refusal is on a change, not on the mention.</b> A client that GETs a profile, edits
     * one field and sends the whole document back names every one of these at its current value, and
     * refusing that would make the honest answer unusable. Carrying a value forward unchanged is not
     * a dropped write, because nothing the caller asked for went missing.
     *
     * <p><b>Not the silence item 46 described, and not its mirror either.</b> There an operator was
     * refused with no reason given; the answer here has to name the field and the endpoint that does
     * own it, or it is the same defect wearing a 400.
     *
     * @param document the raw request document — the only thing that knows which fields were
     *     <em>named</em>, which a bound {@link Profile} cannot tell for an initialised collection.
     * @param incoming the same document bound to an entity.
     * @param stored the row as the service holds it, or {@code null} when there is none yet — in
     *     which case any named value is an introduction and therefore a change.
     */
    static void refuseFieldsThisEndpointDoesNotOwn(ObjectNode document, Profile incoming, Profile stored) {
        List<String> refused = new ArrayList<>();
        if (
            changes(
                document,
                "specialtyCategoryId",
                incoming.getSpecialtyCategoryId(),
                stored == null ? null : stored.getSpecialtyCategoryId()
            )
        ) {
            refused.add("specialtyCategoryId");
        }
        if (
            document.has("teamIds") && !Objects.equals(orEmpty(incoming.getTeamIds()), orEmpty(stored == null ? null : stored.getTeamIds()))
        ) {
            refused.add("teamIds");
        }
        if (
            changes(
                document,
                "pushMessagesEnabled",
                incoming.getPushMessagesEnabled(),
                stored == null ? null : stored.getPushMessagesEnabled()
            )
        ) {
            refused.add("pushMessagesEnabled");
        }
        if (
            changes(
                document,
                "pushComplianceEnabled",
                incoming.getPushComplianceEnabled(),
                stored == null ? null : stored.getPushComplianceEnabled()
            )
        ) {
            refused.add("pushComplianceEnabled");
        }
        if (
            changes(
                document,
                "pushShowSenderName",
                incoming.getPushShowSenderName(),
                stored == null ? null : stored.getPushShowSenderName()
            )
        ) {
            refused.add("pushShowSenderName");
        }
        if (changes(document, "status", incoming.getStatus(), stored == null ? null : stored.getStatus())) {
            refused.add("status");
        }
        if (!refused.isEmpty()) {
            String detail = refused.stream().map(field -> field + " is set by " + REFUSED_FIELDS.get(field)).collect(joining("; "));
            // ResponseStatusException rather than BadRequestAlertException, and not for consistency
            // with anything — for the only reason that matters here, which is that the caller has to
            // be able to read which field was refused. ExceptionTranslator.customizeProblem
            // overwrites `title` with extractTitle(), and getCustomizedTitle() returns null for
            // everything but MethodArgumentNotValidException, so a BadRequestAlertException's
            // defaultMessage never reaches the client: the body carries "Bad Request" and
            // "error.fieldnotpatchable" and nothing else. A refusal that will not say what it refused
            // is item 46 wearing a 400, which is precisely what this method exists to avoid. That the
            // same silence applies to every other BadRequestAlertException in the service is a wider
            // finding and is recorded under item 60 rather than fixed here.
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "This endpoint does not set these fields, and will not quietly drop them: " + detail
            );
        }
    }

    private static boolean changes(ObjectNode document, String field, Object incoming, Object stored) {
        return document.has(field) && !Objects.equals(incoming, stored);
    }

    private static List<String> orEmpty(List<String> ids) {
        return ids == null ? List.of() : ids;
    }
}
