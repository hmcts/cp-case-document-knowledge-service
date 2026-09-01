package uk.gov.hmcts.cp.cdk.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * Binds {@code cdk.monitoring.*} properties (DD-43185, ADR-002).
 *
 * <p>Governs the ShedLock-guarded stuck-work refresh: one shared threshold for both stalled-work
 * aggregates, and the refresh job's own cadence/lock durations. Unlike the discovery schedulers,
 * {@link #enabled} defaults to {@code true} — this refresh is a read-only aggregate count with no
 * side effect on any downstream system, so defaulting it off would reproduce the exact
 * "pod looks healthy and silently publishes nothing" failure mode this ticket exists to fix.
 */
@ConfigurationProperties(prefix = "cdk.monitoring")
public class MonitoringProperties {

    private boolean enabled = true;

    @DurationUnit(ChronoUnit.MINUTES)
    private Duration stalledThreshold = Duration.ofMinutes(30);

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration refreshInterval = Duration.ofMinutes(1);

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration initialDelay = Duration.ofSeconds(30);

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration lockAtLeastFor = Duration.ofSeconds(55);

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration lockAtMostFor = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getStalledThreshold() {
        return stalledThreshold;
    }

    public void setStalledThreshold(final Duration stalledThreshold) {
        this.stalledThreshold = stalledThreshold;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(final Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(final Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public Duration getLockAtLeastFor() {
        return lockAtLeastFor;
    }

    public void setLockAtLeastFor(final Duration lockAtLeastFor) {
        this.lockAtLeastFor = lockAtLeastFor;
    }

    public Duration getLockAtMostFor() {
        return lockAtMostFor;
    }

    public void setLockAtMostFor(final Duration lockAtMostFor) {
        this.lockAtMostFor = lockAtMostFor;
    }
}
