package net.jojoaddison.web.rest;

import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.OnboardingService;
import net.jojoaddison.service.dto.OnboardingProgressDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What is left of {@code /api/onboarding} — the progress meter and the first-login
 * acknowledgement (professional-onboarding-workflow.md § Workflow step 11).
 *
 * <h2>⚠ This class used to be the whole onboarding surface, and is now three mappings</h2>
 *
 * <p>It carried twenty. {@code GET}/{@code PUT /profile} went to {@code ProfileResource} at
 * {@code /api/profile} in F8; the <b>fifteen {@code /applications*} mappings</b> went to
 * {@link ProfessionalApplicationResource} at {@code /api/professional-application} in T3, per
 * {@code profile.md} § Other Elements: <i>"{@code api/onboarding/applications} should migrate to
 * {@code api/professional-application}"</i>. {@code ComplianceResource} went with them, into the
 * same class.
 *
 * <p>⛔ <b>The three below are deliberately untouched: they are T5's.</b> {@code profile.md}
 * § Gap Update — <i>"The progress meter covers exactly these 4 steps and all their respective
 * requirements"</i> — makes {@code /progress} a refactor rather than a rename, and
 * {@code mobile/} reads it today. The acknowledgement pair is step 11's and no task has reached it.
 * Moving them here would have been a second, unreviewed decision about where they belong.
 *
 * <p>⚠ <b>The {@code .authenticated()} rule on {@code /api/onboarding/**} is still what admits
 * these three</b>, and it is still the right gate: {@code /progress} and the acknowledgement both
 * resolve the caller from the {@code uid} claim and name nobody else, and an applicant holds
 * {@code ROLE_USER} and nothing else.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingResource {

    private final OnboardingService onboardingService;

    public OnboardingResource(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /**
     * How far the caller has got, for the meter on {@code /account/profile}.
     *
     * <p>Always answers, including for an account with no application at all — everything false,
     * 0% — because that is the state a clinician created by admin invitation starts in, and the
     * profile page has to render something for them rather than an error.
     */
    @GetMapping("/progress")
    public OnboardingProgressDTO progress() {
        return onboardingService.progressFor(currentAccountId());
    }

    public record AcknowledgementStatus(boolean acknowledged) {}

    @GetMapping("/acknowledgement")
    public AcknowledgementStatus acknowledgementStatus() {
        return new AcknowledgementStatus(onboardingService.hasAcknowledgedFirstLogin(currentAccountId()));
    }

    @PostMapping("/acknowledgement")
    public ResponseEntity<OnboardingEvent> acknowledge() {
        return ResponseEntity.status(HttpStatus.CREATED).body(onboardingService.acknowledgeFirstLogin(currentAccountId()));
    }

    /**
     * The caller's gateway {@code User.id} — what {@code ProfessionalApplication.accountId} and
     * {@code Profile.accountId} hold and are looked up by (backlog.md item 50).
     *
     * <p>This returned {@code SecurityUtils.getCurrentUserLogin()} until that item, and the two
     * identifier spaces have been unified onto the id. A token carrying no {@code uid} claim — one
     * minted before 2026-09-07, or by hc-admin or hc-patient — resolves to nobody and is refused
     * here rather than falling back to the subject, which would key this database on a value it no
     * longer stores. Signing in again mints a token that carries the claim.
     *
     * <p>⚠ The {@code currentActor()} accessor this class also carried went to
     * {@link ProfessionalApplicationResource} with the mappings that needed it. The two were
     * deliberately separate — <b>audit fields name a person for a person to read and are never
     * looked up</b> — and they still are, over there.
     */
    private String currentAccountId() {
        return SecurityUtils.getCurrentAccountId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }
}
