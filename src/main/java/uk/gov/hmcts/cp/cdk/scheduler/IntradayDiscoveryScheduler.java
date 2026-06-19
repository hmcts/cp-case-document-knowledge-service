package uk.gov.hmcts.cp.cdk.scheduler;

import uk.gov.hmcts.cp.cdk.services.DiscoveryService;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every 10 minutes during court hours (08:00–18:00 by default, configurable).
 * Targets late-arriving IDPCs, schedule changes and late list additions.
 */
@Slf4j
@Component
public class IntradayDiscoveryScheduler {

    private final DiscoveryService discoveryService;

    public IntradayDiscoveryScheduler(final DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Scheduled(cron = "${scheduler.intraday-discovery.cron:0 0/10 7-19 * * MON-FRI}")
    @SchedulerLock(name = "${scheduler.intraday-discovery.name:intradayDiscoveryScheduler}",
            lockAtLeastFor = "${scheduler.intraday-discovery.lock-at-least-for:PT8M}",
            lockAtMostFor = "${scheduler.intraday-discovery.lock-at-most-for:PT9M}")
    public void run() {
        log.info("Intraday discovery starting");
        discoveryService.runIntradayDiscovery();
        log.info("Intraday discovery finished");
    }
}
