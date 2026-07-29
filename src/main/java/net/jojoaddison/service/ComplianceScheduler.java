package net.jojoaddison.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly WP7 compliance sweep (scheduling is enabled in AsyncConfiguration).
 * The same sweep is exposed to administrators as an on-demand endpoint in
 * ComplianceResource; both paths are idempotent per day.
 */
@Component
public class ComplianceScheduler {

    private static final Logger log = LoggerFactory.getLogger(ComplianceScheduler.class);

    private final ComplianceService complianceService;

    public ComplianceScheduler(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void nightlySweep() {
        ComplianceService.SweepResult result = complianceService.sweepExpiredLicenses("system");
        log.info("Nightly compliance sweep finished: {}", result);
    }
}
