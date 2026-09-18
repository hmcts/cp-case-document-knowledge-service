package uk.gov.hmcts.cp.cdk.scheduler;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationScope;
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

    private static final String INTRADAY_DISCOVERY = "intraday-discovery";

    private final DiscoveryService discoveryService;

    public IntradayDiscoveryScheduler(final DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Scheduled(cron = "${scheduler.intraday-discovery.cron:0 0/10 7-19 * * MON-FRI}")
    @SchedulerLock(name = "${scheduler.intraday-discovery.name:intradayDiscoveryScheduler}",
            lockAtLeastFor = "${scheduler.intraday-discovery.lock-at-least-for:PT8M}",
            lockAtMostFor = "${scheduler.intraday-discovery.lock-at-most-for:PT9M}")
    @SuppressWarnings("PMD.UnusedLocalVariable") // the try-with-resources variable is used for its close()
    public void run() {
        try (CorrelationScope scope = CorrelationScope.openIfAbsent()) {
            log.info("Intraday discovery starting scheduler={}", INTRADAY_DISCOVERY);
            try {
                discoveryService.runIntradayDiscovery();
                log.info("Intraday discovery finished scheduler={}", INTRADAY_DISCOVERY);
            } catch (final Exception e) {
                log.error("Intraday discovery failed scheduler={}", INTRADAY_DISCOVERY, e);
            }
        }
    }
}
