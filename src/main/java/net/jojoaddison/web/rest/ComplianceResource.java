package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.domain.OnboardingEvent;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.ComplianceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WP7 admin-only compliance and operations surface: on-demand expiry sweep,
 * expiring-license watchlist, per-status/per-source funnel metrics (careers
 * task 145), and the cross-application audit feed.
 */
@RestController
@RequestMapping("/api/onboarding/compliance")
@PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
public class ComplianceResource {

    private static final Logger log = LoggerFactory.getLogger(ComplianceResource.class);

    private final ComplianceService complianceService;

    public ComplianceResource(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @PostMapping("/sweep")
    public ComplianceService.SweepResult sweep() {
        String actor = SecurityUtils.getCurrentUserLogin().orElse("system");
        log.debug("REST request by {} to run the compliance sweep", actor);
        return complianceService.sweepExpiredLicenses(actor);
    }

    @GetMapping("/expiring")
    public List<ComplianceService.ExpiringLicense> expiring(@RequestParam(defaultValue = "30") int days) {
        return complianceService.expiringLicenses(days);
    }

    @GetMapping("/metrics")
    public ComplianceService.OnboardingMetrics metrics() {
        return complianceService.metrics();
    }

    @GetMapping("/events")
    public List<OnboardingEvent> recentEvents() {
        return complianceService.recentEvents();
    }
}
