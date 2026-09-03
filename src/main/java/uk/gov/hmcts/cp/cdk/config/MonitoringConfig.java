package uk.gov.hmcts.cp.cdk.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Registers {@link MonitoringProperties} and validates its shipped shape at startup
 * (ADR-002). {@code refresh-interval} and {@code lock-at-least-for} are coupled — the
 * "one refresh per cadence, cluster-wide" property FR-004/ADR-008 rely on holds only while
 * {@code lock-at-least-for} is close to {@code refresh-interval}. A misconfiguration here is
 * logged and startup continues (NFR-004) — it is never worth failing the pod over.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfig {

    private static final long FR004_MIN_REFRESH_SECONDS = 60L;
    private static final double LOCK_AT_LEAST_FOR_MIN_RATIO = 0.9;

    private final MonitoringProperties properties;

    public MonitoringConfig(final MonitoringProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateShippedShapeAtStartup() {
        final long refreshSeconds = properties.getRefreshInterval().getSeconds();
        final long lockAtLeastForSeconds = properties.getLockAtLeastFor().getSeconds();
        final long minLockAtLeastForSeconds = (long) (refreshSeconds * LOCK_AT_LEAST_FOR_MIN_RATIO);

        if (refreshSeconds < FR004_MIN_REFRESH_SECONDS) {
            log.warn("cdk.monitoring.refresh-interval={}s is below FR-004's {}s floor",
                    refreshSeconds, FR004_MIN_REFRESH_SECONDS);
        }
        if (lockAtLeastForSeconds < minLockAtLeastForSeconds) {
            log.warn("cdk.monitoring.lock-at-least-for={}s is short relative to "
                            + "refresh-interval={}s; more than one pod may refresh per cadence",
                    lockAtLeastForSeconds, refreshSeconds);
        }
    }
}
