package net.jojoaddison.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly purge of customer snapshots older than the retention window (docs/duty-roster.md § 6, DR2).
 *
 * <p>Scheduling is enabled in {@code AsyncConfiguration}. The same purge is exposed to administrators
 * on demand at {@code POST /api/duty-roster/purge-snapshots}, and both paths are idempotent, exactly
 * as {@link ComplianceScheduler} and its endpoint are.
 *
 * <p>Kept separate from that scheduler rather than folded into it. They run for different reasons —
 * one enforces licensing, this one enforces data retention — and a failure in either should be
 * readable on its own. It runs at 04:30, half an hour after the compliance sweep, so the two are not
 * competing for the same connections and their log lines do not interleave.
 */
@Component
public class VisitSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(VisitSnapshotScheduler.class);

    private final DutyRosterService dutyRosterService;

    public VisitSnapshotScheduler(DutyRosterService dutyRosterService) {
        this.dutyRosterService = dutyRosterService;
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void nightlyPurge() {
        DutyRosterService.PurgeResult result = dutyRosterService.purgeExpiredSnapshots();
        if (result.visitsPurged() > 0) {
            log.info("Nightly visit-snapshot purge finished: {}", result);
        }
    }
}
