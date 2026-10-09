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
 * The credential reviewer's verdict on a document — {@code PUT /api/personal-document/{id}/verify}
 * and {@code .../reject} (professional-onboarding-workflow.md § Documents, step 6).
 *
 * <h2>⚠ This class was {@code OnboardingDocumentResource} on {@code /api/onboarding/documents}</h2>
 *
 * <p>{@code profile.md} § Other Elements: <i>"{@code api/onboarding/document} should migrate to
 * {@code api/personal-document}"</i>. T2 moved the applicant's three mappings — the upload, the
 * own-document list and {@code GET /{id}/content} — into {@link OwnPersonalDocumentResource} and
 * left these two behind for T3, which is the task that owns the rest of the admin surface. They are
 * now on the base the specification names, and <b>the class was renamed with its path</b>: a class
 * called {@code OnboardingDocumentResource} serving {@code /api/personal-document} is exactly the
 * stale name this estate has been bitten by, and nothing fails when one is left behind.
 *
 * <p><b>Two classes serve one base path and that is deliberate here, unlike the application
 * surface.</b> {@link OwnPersonalDocumentResource} is {@code .authenticated()} and never echoes
 * document bytes back; these two verbs are {@code ROLE_ADMIN} and are a different caller. T3
 * dissolved {@code ComplianceResource} into {@link ProfessionalApplicationResource} because
 * {@code profile.md} names <em>that</em> file for <em>that</em> endpoint; it names no file for
 * {@code api/personal-document}, so the split by gate is kept.
 *
 * <p>⛔ <b>The {@code @PreAuthorize} below is not a second layer — on this path it is the only one
 * that was there, and T3 added the first.</b> The chain rule for
 * {@code /api/personal-document/**} is deliberately wide: it is the applicant island, so until
 * this task the annotation was the whole of what stood between a carer and a credential verdict.
 * {@code api/SecurityConfiguration} now also carries a {@code PUT} rule on
 * {@code /api/personal-document/*&#47;verify} and {@code .../reject} at {@code ROLE_ADMIN}, so there
 * are two. ⛔ Do not delete either on the strength of the other; that is the near-miss
 * {@code docs/CLAUDE.md} records, where the layer a comment called operative did not hold.
 */
@RestController
@RequestMapping("/api/personal-document")
public class PersonalDocumentReviewResource {

    private final OnboardingService onboardingService;

    public PersonalDocumentReviewResource(OnboardingService onboardingService) {
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
     * <em>for a person to read</em> and are never looked up, which is why
     * {@code ProfessionalApplicationResource} keeps {@code currentAccountId()} and
     * {@code currentActor()} as separate accessors.
     */
    private String currentLogin() {
        return SecurityUtils.getCurrentUserLogin().orElse("system");
    }
}
