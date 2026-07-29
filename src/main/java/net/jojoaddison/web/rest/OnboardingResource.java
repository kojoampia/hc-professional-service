package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.OnboardingService;
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
            request.requestedRole(),
            request.consentAccepted(),
            null,
            request.source()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(application);
    }

    @GetMapping("/profile")
    public net.jojoaddison.domain.Profile getOwnProfile() {
        return onboardingService.getOwnProfile(currentAccountId());
    }

    @PutMapping("/profile")
    public net.jojoaddison.domain.Profile upsertOwnProfile(@RequestBody net.jojoaddison.domain.Profile profile) {
        return onboardingService.upsertOwnProfile(currentAccountId(), profile);
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
        return onboardingService.decide(id, request.decision(), request.reason(), request.correctionNotes(), currentAccountId());
    }

    @PutMapping("/applications/{id}/organization")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication assignOrganization(@PathVariable String id, @RequestBody OrganizationRequest request) {
        return onboardingService.assignOrganization(
            id,
            request.specialtyCategoryId(),
            request.teamIds(),
            request.supervisorProfileId(),
            currentAccountId()
        );
    }

    @PutMapping("/applications/{id}/authority-assigned")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication markAuthorityAssigned(@PathVariable String id) {
        return onboardingService.markStatus(id, OnboardingStatus.AUTHORITY_ASSIGNED, "clinical authority assigned", currentAccountId());
    }

    @PutMapping("/applications/{id}/roster-configured")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication markRosterConfigured(@PathVariable String id) {
        return onboardingService.markStatus(id, OnboardingStatus.ROSTER_CONFIGURED, "duty roster configured", currentAccountId());
    }

    @PutMapping("/applications/{id}/activate")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication activate(@PathVariable String id) {
        return onboardingService.markStatus(id, OnboardingStatus.ACTIVE, "professional access activated", currentAccountId());
    }

    @PutMapping("/applications/{id}/suspend")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication suspend(@PathVariable String id, @RequestBody StatusRequest request) {
        return onboardingService.markStatus(id, OnboardingStatus.SUSPENDED, request.reason(), currentAccountId());
    }

    @PutMapping("/applications/{id}/deactivate")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ProfessionalApplication deactivate(@PathVariable String id, @RequestBody StatusRequest request) {
        return onboardingService.markStatus(id, OnboardingStatus.DEACTIVATED, request.reason(), currentAccountId());
    }

    private void assertAdminOrOwner(ProfessionalApplication application) {
        boolean admin = SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN);
        if (!admin && !currentAccountId().equals(application.getAccountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the application owner");
        }
    }

    private String currentAccountId() {
        return SecurityUtils.getCurrentUserLogin()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }
}
