package uk.gov.hmcts.cp.cdk.scheduler;

import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.INTRADAY_DISCOVERY;

import uk.gov.hmcts.cp.cdk.metrics.SchedulerMetrics;
import uk.gov.hmcts.cp.cdk.services.DiscoveryService;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every 10 minutes during court hours (08:00–18:00 by default, configurable).
 * Targets late-arriving IDPCs, schedule changes and late list additions.
 */
@ConditionalOnProperty(
        name = "scheduler.intraday-discovery.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
@Component
public class IntradayDiscoveryScheduler {

    private final DiscoveryService discoveryService;
    private final SchedulerMetrics schedulerMetrics;

    public IntradayDiscoveryScheduler(final DiscoveryService discoveryService,
                                       final SchedulerMetrics schedulerMetrics) {
        this.discoveryService = discoveryService;
        this.schedulerMetrics = schedulerMetrics;
    }

    @Scheduled(cron = "${scheduler.intraday-discovery.cron:0 0/10 7-19 * * MON-FRI}")
    @SchedulerLock(name = "${scheduler.intraday-discovery.name:intradayDiscoveryScheduler}",
            lockAtLeastFor = "${scheduler.intraday-discovery.lock-at-least-for:PT8M}",
            lockAtMostFor = "${scheduler.intraday-discovery.lock-at-most-for:PT9M}")
    public void run() {
        log.info("Intraday discovery starting scheduler={}", INTRADAY_DISCOVERY);
        boolean success = false;
        try {
            discoveryService.runIntradayDiscovery();
            success = true;
            log.info("Intraday discovery finished scheduler={}", INTRADAY_DISCOVERY);
        } catch (final Exception e) {
            log.error("Intraday discovery failed scheduler={}", INTRADAY_DISCOVERY, e);
        } finally {
            schedulerMetrics.recordRun(INTRADAY_DISCOVERY, success);
        }
    }
}
