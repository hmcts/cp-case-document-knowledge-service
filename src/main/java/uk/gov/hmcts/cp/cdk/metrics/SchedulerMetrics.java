package uk.gov.hmcts.cp.cdk.metrics;

import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.INTRADAY_DISCOVERY;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.NIGHTLY_DISCOVERY;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.OUTCOME_FAILURE;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.OUTCOME_SUCCESS;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.SCHEDULER_ENABLED;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.SCHEDULER_LAST_SUCCESS;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.SCHEDULER_RUNS;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.TAG_OUTCOME;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.TAG_SCHEDULER;

import uk.gov.hmcts.cp.cdk.scheduler.IntradayDiscoveryScheduler;
import uk.gov.hmcts.cp.cdk.scheduler.NightlyDiscoveryScheduler;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;
import uk.gov.hmcts.cp.cdk.util.TimeUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Always-present scheduler run-outcome and heartbeat observability (ADR-006, ADR-007).
 *
 * <p>Unlike {@code IntradayDiscoveryScheduler} / {@code NightlyDiscoveryScheduler}, this bean
 * carries no {@code @ConditionalOnProperty} — it must exist regardless of whether either
 * scheduler bean does, so that a disabled scheduler is still visible rather than silently
 * absent (Story 2, AC-020).
 *
 * <p>Every series this class owns is pre-registered at construction, at value {@code 0}
 * (AC-005). An un-incremented counter is otherwise absent from a scrape, and
 * {@code increase(cdk_scheduler_runs_total[45m]) == 0} over an absent series returns
 * <em>no data</em> rather than {@code 0} — silently defeating the liveness alert this metric
 * exists to support.
 *
 * <p>Also owns {@code cdk.scheduler.enabled} (Story 2, ADR-006) — fixed at startup from the
 * bound {@link SchedulerProperties}, since the underlying scheduler bean set is itself fixed at
 * startup by {@code @ConditionalOnProperty}. An {@link ApplicationReadyEvent} listener logs each
 * scheduler's configured state once, and cross-checks it against actual bean presence via a lazy
 * {@link ObjectProvider} so configuration drift is visible without ever blocking startup.
 */
@Slf4j
@Component
public class SchedulerMetrics {

    private final Map<String, Counter> runCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastSuccessBySchedulerTag = new ConcurrentHashMap<>();
    private final SchedulerProperties schedulerProperties;
    private final ObjectProvider<IntradayDiscoveryScheduler> intradaySchedulerProvider;
    private final ObjectProvider<NightlyDiscoveryScheduler> nightlySchedulerProvider;

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    // Constructor-only bootstrap over the two fixed schedulers, not a hot path.
    public SchedulerMetrics(final MeterRegistry registry,
                             final SchedulerProperties schedulerProperties,
                             final ObjectProvider<IntradayDiscoveryScheduler> intradaySchedulerProvider,
                             final ObjectProvider<NightlyDiscoveryScheduler> nightlySchedulerProvider) {
        this.schedulerProperties = schedulerProperties;
        this.intradaySchedulerProvider = intradaySchedulerProvider;
        this.nightlySchedulerProvider = nightlySchedulerProvider;

        for (final Spec spec : List.of(
                new Spec(INTRADAY_DISCOVERY, schedulerProperties.getIntradayDiscovery().isEnabled()),
                new Spec(NIGHTLY_DISCOVERY, schedulerProperties.getNightlyDiscovery().isEnabled()))) {
            final String schedulerTag = spec.tag();

            Gauge.builder(SCHEDULER_ENABLED, () -> spec.enabled() ? 1d : 0d)
                    .description("1 if this discovery scheduler is enabled in configuration, else 0")
                    .tag(TAG_SCHEDULER, schedulerTag)
                    .strongReference(true)
                    .register(registry);

            final AtomicLong lastSuccess = new AtomicLong(0L);
            lastSuccessBySchedulerTag.put(schedulerTag, lastSuccess);
            Gauge.builder(SCHEDULER_LAST_SUCCESS, lastSuccess, AtomicLong::doubleValue)
                    .description("Epoch seconds of the last run of this scheduler that completed without throwing")
                    .tag(TAG_SCHEDULER, schedulerTag)
                    .strongReference(true)
                    .register(registry);

            for (final String outcome : List.of(OUTCOME_SUCCESS, OUTCOME_FAILURE)) {
                runCounters.put(key(schedulerTag, outcome),
                        Counter.builder(SCHEDULER_RUNS)
                                .description("Discovery scheduler run outcomes")
                                .tag(TAG_SCHEDULER, schedulerTag)
                                .tag(TAG_OUTCOME, outcome)
                                .register(registry));
            }
        }
    }

    /**
     * Records the outcome of one scheduler run. Increments the matching
     * {@code cdk.scheduler.runs} counter exactly once, and — on success only — advances the
     * heartbeat gauge to the current time. Callers invoke this from a {@code finally} block
     * driven by a {@code boolean success} flag, so exactly one outcome is recorded per
     * invocation regardless of how the run completed.
     *
     * <p>Map lookups only; this method cannot realistically throw (NFR-004).
     *
     * @param schedulerTag one of {@link CdkMeters#INTRADAY_DISCOVERY} / {@link CdkMeters#NIGHTLY_DISCOVERY}
     * @param success      {@code true} if the run completed without throwing
     */
    public void recordRun(final String schedulerTag, final boolean success) {
        runCounters.get(key(schedulerTag, success ? OUTCOME_SUCCESS : OUTCOME_FAILURE)).increment();
        if (success) {
            lastSuccessBySchedulerTag.get(schedulerTag).set(TimeUtils.utcNow().toEpochSecond());
        }
    }

    /**
     * Logs each scheduler's configured enabled state once per context, and warns if the bound
     * flag disagrees with whether the corresponding scheduler bean actually exists. Anchored to
     * {@link ApplicationReadyEvent} rather than the constructor so every
     * {@code @ConditionalOnProperty} has already been evaluated (AC-003, AC-004, AC-022).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logConfiguredStateAndCheckForDrift() {
        logStateAndWarnOnDrift(INTRADAY_DISCOVERY,
                schedulerProperties.getIntradayDiscovery().isEnabled(),
                intradaySchedulerProvider.getIfAvailable() != null);
        logStateAndWarnOnDrift(NIGHTLY_DISCOVERY,
                schedulerProperties.getNightlyDiscovery().isEnabled(),
                nightlySchedulerProvider.getIfAvailable() != null);
    }

    private void logStateAndWarnOnDrift(final String schedulerTag, final boolean enabled,
                                         final boolean beanPresent) {
        log.info("Discovery scheduler configuration scheduler={} enabled={}", schedulerTag, enabled);
        if (enabled != beanPresent) {
            log.warn("Discovery scheduler configuration drift scheduler={} enabled={} beanPresent={}",
                    schedulerTag, enabled, beanPresent);
        }
    }

    private static String key(final String schedulerTag, final String outcome) {
        return schedulerTag + '|' + outcome;
    }

    private record Spec(String tag, boolean enabled) {
    }
}
