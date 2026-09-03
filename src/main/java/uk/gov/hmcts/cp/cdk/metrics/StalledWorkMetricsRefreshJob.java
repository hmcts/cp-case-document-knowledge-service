package uk.gov.hmcts.cp.cdk.metrics;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules {@link StalledWorkMetrics#refresh()} (DD-43185, FR-004). Gated by
 * {@code cdk.monitoring.enabled} (default {@code true}) — this flag controls only whether this
 * job runs, never whether {@link StalledWorkMetrics}'s gauges exist; the gauges are always
 * registered so a disabled refresh is visible as stale values, not as missing series.
 *
 * <p>The lock name {@code stalledWorkMetricsRefresh} is a literal constant, not a property
 * placeholder (ADR-008) — unlike the two discovery schedulers, this identity must never drift
 * with an environment override. {@code lockAtMostFor} explicitly overrides
 * {@code ShedLockConfig}'s {@code PT30S} global default, which is shorter than this job's own
 * refresh cadence.
 */
@ConditionalOnProperty(name = "cdk.monitoring.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@Component
public class StalledWorkMetricsRefreshJob {

    public static final String LOCK_NAME = "stalledWorkMetricsRefresh";

    private final StalledWorkMetrics stalledWorkMetrics;

    public StalledWorkMetricsRefreshJob(final StalledWorkMetrics stalledWorkMetrics) {
        this.stalledWorkMetrics = stalledWorkMetrics;
    }

    @Scheduled(fixedDelayString = "${cdk.monitoring.refresh-interval:PT1M}",
            initialDelayString = "${cdk.monitoring.initial-delay:PT30S}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtLeastFor = "${cdk.monitoring.lock-at-least-for:PT55S}",
            lockAtMostFor = "${cdk.monitoring.lock-at-most-for:PT5M}")
    public void run() {
        try {
            stalledWorkMetrics.refresh();
        } catch (final Exception e) {
            // StalledWorkMetrics.refresh() already contains its own per-aggregate failures;
            // this is a last-resort backstop so nothing from this job ever escapes into
            // Spring's TaskScheduler (FR-006).
            log.warn("Stalled-work metrics refresh job failed unexpectedly", e);
        }
    }
}
