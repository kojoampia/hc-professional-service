package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.OnboardingService;
import net.jojoaddison.service.dto.OnboardingProgressDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Onboarding application lifecycle endpoints
 * (professional-onboarding-workflow.md § Workflow steps 3, 7, 8 and the
 * § Status model). Applicant endpoints operate on the caller's own
 * application; reviewer/administrator endpoints require ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingResource {

    private static final Logger log = LoggerFactory.getLogger(OnboardingResource.class);

    private final OnboardingService onboardingService;

    public OnboardingResource(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    public record StartApplicationRequest(String requestedRole, boolean consentAccepted, String source) {}

    public record DecisionRequest(OnboardingStatus decision, String reason, String correctionNotes) {}

    public record OrganizationRequest(String specialtyCategoryId, List<String> teamIds, String supervisorProfileId) {}

    public record StatusRequest(String reason) {}

    @PostMapping("/applications")
    public ResponseEntity<ProfessionalApplication> startApplication(@RequestBody StartApplicationRequest request) {
        String accountId = currentAccountId();
        log.debug("REST request to start onboarding application for {}", accountId);
        ProfessionalApplication application = onboardingService.startApplication(
            accountId,
            currentActor(),
            request.requestedRole(),
            request.consentAccepted(),
            null,
            request.source()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(application);
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

    @GetMapping("/profile")
    public net.jojoaddison.domain.Profile getOwnProfile() {
        return onboardingService.getOwnProfile(currentAccountId());
    }

    @PutMapping("/profile")
    public net.jojoaddison.domain.Profile upsertOwnProfile(@RequestBody net.jojoaddison.domain.Profile profile) {
        return onboardingService.upsertOwnProfile(currentAccountId(), profile);
    }

    @GetMapping("/applications")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public List<ProfessionalApplication> listApplications(
        @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) OnboardingStatus status
    ) {
        return onboardingService.listApplications(status);
    }

    @GetMapping("/applications/{id}/documents")
    public List<net.jojoaddison.domain.PersonalDocument> applicationDocuments(@PathVariable String id) {
        assertAdminOrOwner(onboardingService.getById(id));
        return onboardingService
            .documentsForApplication(id)
            .stream()
            .map(document -> {
                document.setData(null);
                return document;
            })
            .toList();
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

    @GetMapping("/applications/me")
    public ProfessionalApplication getOwnApplication() {
        return onboardingService.getOwnApplication(currentAccountId());
    }

    @PutMapping("/applications/me/complete-profile")
    public ProfessionalApplication completeProfile() {
        return onboardingService.completeProfile(currentAccountId());
    }

    @PutMapping("/applications/me/submit")
    public ProfessionalApplication submitForReview() {
        return onboardingService.submitForReview(currentAccountId());
    }

    @GetMapping("/applications/{id}")
    public ProfessionalApplication getApplication(@PathVariable String id) {
        ProfessionalApplication application = onboardingService.getById(id);
        assertAdminOrOwner(application);
        return application;
    }

    @GetMapping("/applications/{id}/events")
    public List<OnboardingEvent> getEvents(@PathVariable String id) {
        assertAdminOrOwner(onboardingService.getById(id));
        return onboardingService.eventsFor(id);
    }

    @PutMapping("/applications/{id}/decide")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication decide(@PathVariable String id, @RequestBody DecisionRequest request) {
        return onboardingService.decide(id, request.decision(), request.reason(), request.correctionNotes(), currentActor());
    }

    @PutMapping("/applications/{id}/organization")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication assignOrganization(@PathVariable String id, @RequestBody OrganizationRequest request) {
        return onboardingService.assignOrganization(
            id,
            request.specialtyCategoryId(),
            request.teamIds(),
            request.supervisorProfileId(),
            currentActor()
        );
    }

    @PutMapping("/applications/{id}/authority-assigned")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication markAuthorityAssigned(@PathVariable String id) {
        return onboardingService.markStatus(id, OnboardingStatus.AUTHORITY_ASSIGNED, "clinical authority assigned", currentActor());
    }

    @PutMapping("/applications/{id}/roster-configured")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication markRosterConfigured(@PathVariable String id) {
        return onboardingService.markStatus(id, OnboardingStatus.ROSTER_CONFIGURED, "duty roster configured", currentActor());
    }

    @PutMapping("/applications/{id}/activate")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication activate(@PathVariable String id) {
        return onboardingService.markStatus(id, OnboardingStatus.ACTIVE, "professional access activated", currentActor());
    }

    @PutMapping("/applications/{id}/suspend")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication suspend(@PathVariable String id, @RequestBody StatusRequest request) {
        return onboardingService.markStatus(id, OnboardingStatus.SUSPENDED, request.reason(), currentActor());
    }

    @PutMapping("/applications/{id}/deactivate")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication deactivate(@PathVariable String id, @RequestBody StatusRequest request) {
        return onboardingService.markStatus(id, OnboardingStatus.DEACTIVATED, request.reason(), currentActor());
    }

    private void assertAdminOrOwner(ProfessionalApplication application) {
        boolean admin = SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN);
        if (!admin && !currentAccountId().equals(application.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the application owner");
        }
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
     */
    private String currentAccountId() {
        return SecurityUtils.getCurrentAccountId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }

    /**
     * The caller's login, for the {@code actor} on an {@code OnboardingEvent} and on the domain
     * events a transition publishes. Deliberately not {@link #currentAccountId()}: a trail an
     * administrator reads should name a person, and nothing is ever looked up by it.
     */
    private String currentActor() {
        return SecurityUtils.getCurrentUserLogin().orElse("system");
    }
}
