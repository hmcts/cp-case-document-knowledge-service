package uk.gov.hmcts.cp.cdk.scheduler;

import uk.gov.hmcts.cp.cdk.services.DiscoveryService;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs once per day at 02:00 to pre-load scheduled hearings from today to configured number of days ahead.
 */
@ConditionalOnProperty(
        name = "scheduler.nightly-discovery.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
@Component
public class NightlyDiscoveryScheduler {

    private final DiscoveryService discoveryService;

    public NightlyDiscoveryScheduler(final DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Scheduled(cron = "${scheduler.nightly-discovery.cron:0 0 2 * * *}")
    @SchedulerLock(name = "${scheduler.nightly-discovery.name:nightlyDiscoveryScheduler}",
            lockAtLeastFor = "${scheduler.nightly-discovery.lock-at-least-for:PT1H}",
            lockAtMostFor = "${scheduler.nightly-discovery.lock-at-most-for:PT2H}")
    public void run() {
        log.info("Nightly discovery starting");
        discoveryService.runNightlyDiscovery();
        log.info("Nightly discovery finished");
    }
}
