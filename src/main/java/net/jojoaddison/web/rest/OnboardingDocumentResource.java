package net.jojoaddison.web.rest;

import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.OnboardingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The credential reviewer's verdict on a document — {@code PUT /api/onboarding/documents/{id}/verify}
 * and {@code .../reject} (professional-onboarding-workflow.md § Documents, step 6).
 *
 * <h2>⚠ This class used to be the whole document surface, and is now only its admin half</h2>
 *
 * <p>The applicant's three mappings moved to {@link OwnPersonalDocumentResource} at
 * {@code /api/personal-document} in T2 (profile.md step 3): the multipart upload, the own-document
 * list and {@code GET /{id}/content}. Everything that made them work — the 5 MB ceiling, the
 * allowlist, the magic-byte check, {@code assertOwnerOrReviewer} and the {@code data}-nulling — went
 * with them and is documented there.
 *
 * <p>⛔ <b>What is left here is reviewer-only and migrates with T3</b>, which absorbs
 * {@code OnboardingResource}'s fourteen {@code /applications} mappings and {@code ComplianceResource}
 * into {@code /api/professional-application}. These two verbs were deliberately <em>not</em> moved
 * with the applicant's: they are a different caller, a different gate ({@code ROLE_ADMIN}, not
 * {@code .authenticated()}) and a different task, and one class holding two answers to "who may call
 * this" is the shape {@code api/} PR #55 exists because of.
 *
 * <p>So two paths serve one collection until T3 lands. That is a transitional state with a task
 * behind it, not a design — and it is the deliberate direction: <b>add before removing, and remove
 * the consumer before the producer</b>. {@code web/}'s review pages call these two and nothing else
 * under this prefix.
 *
 * <p>⚠ <b>The {@code .authenticated()} rule on {@code /api/onboarding/**} is still what admits the
 * request to the service</b>, and the {@code @PreAuthorize} below is what refuses a non-reviewer. The
 * chain rule for this prefix is deliberately wide — it is the applicant island — so on this resource
 * the annotation is not a second layer behind a narrower rule, it is the <em>only</em> thing between
 * a carer and a credential verdict. ⛔ Do not remove it on the reasoning that two layers exist; here
 * there is one.
 */
@RestController
@RequestMapping("/api/onboarding/documents")
public class OnboardingDocumentResource {

    private final OnboardingService onboardingService;

    public OnboardingDocumentResource(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    public record RejectRequest(String reason) {}

    @PutMapping("/{id}/verify")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public PersonalDocument verify(@PathVariable String id) {
        PersonalDocument document = onboardingService.verifyDocument(id, currentLogin());
        document.setData(null);
        return document;
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public PersonalDocument reject(@PathVariable String id, @RequestBody RejectRequest request) {
        PersonalDocument document = onboardingService.rejectDocument(id, request.reason(), currentLogin());
        document.setData(null);
        return document;
    }

    /**
     * The reviewer's login, which is what an {@code OnboardingEvent} actor records.
     *
     * <p>A login and not an account id, deliberately: audit fields in this service name a person
     * <em>for a person to read</em> and are never looked up, which is why {@code OnboardingResource}
     * keeps {@code currentAccountId()} and {@code currentActor()} as separate accessors.
     */
    private String currentLogin() {
        return SecurityUtils.getCurrentUserLogin().orElse("system");
    }
}
