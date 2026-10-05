package uk.gov.hmcts.cp.cdk.scheduler;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationScope;
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

    private static final String NIGHTLY_DISCOVERY = "nightly-discovery";

    private final DiscoveryService discoveryService;

    public NightlyDiscoveryScheduler(final DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Scheduled(cron = "${scheduler.nightly-discovery.cron:0 0 2 * * *}")
    @SchedulerLock(name = "${scheduler.nightly-discovery.name:nightlyDiscoveryScheduler}",
            lockAtLeastFor = "${scheduler.nightly-discovery.lock-at-least-for:PT1H}",
            lockAtMostFor = "${scheduler.nightly-discovery.lock-at-most-for:PT2H}")
    @SuppressWarnings("PMD.UnusedLocalVariable") // the try-with-resources variable is used for its close()
    public void run() {
        try (CorrelationScope scope = CorrelationScope.openIfAbsent()) {
            log.info("Nightly discovery starting scheduler={}", NIGHTLY_DISCOVERY);
            try {
                discoveryService.runNightlyDiscovery();
                log.info("Nightly discovery finished scheduler={}", NIGHTLY_DISCOVERY);
            } catch (final Exception e) {
                log.error("Nightly discovery failed scheduler={}", NIGHTLY_DISCOVERY, e);
            }
        }
    }
}
