package uk.gov.hmcts.cp.cdk.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Failure containment for metric <em>tag computation</em> (DD-43182, ADR-010, FR-014/FR-015).
 *
 * <p>Every meter this ticket adds is pre-registered at construction, so the recording call itself
 * (a map lookup plus an atomic add) cannot realistically throw. What <em>can</em> throw is
 * computing the tag values on the business path — an exception cause-chain walk, a
 * {@code Duration.between(...)} over an unexpectedly null timestamp, a reflective annotation
 * lookup. {@link #runSafely(Runnable)} contains exactly that computation, never the business call
 * itself: the caller wraps only the metric-recording call, so a metrics bug can never change the
 * business outcome (same HTTP status/body, same persisted phase, same {@code ExecutionInfo}, same
 * propagated exception).
 *
 * <p>{@code Error}s propagate — only {@link Exception} is caught (PMD's
 * {@code errorprone.AvoidCatchingThrowable}). At most one WARN is logged per 60 seconds, globally
 * across every call site, carrying the count of failures suppressed in that window — a per-site
 * throttle would still emit a dozen WARNs a minute across this ticket's eleven external-call sites
 * plus the retry aspect.
 */
@Slf4j
public final class MetricsSafety {

    private static final long WARN_WINDOW_SECONDS = 60L;

    /**
     * Test-only seam (OQ-030): package-private, no public setter, no property binding — production
     * code never passes anything but the default. Lets the 60-second throttle window be tested
     * without sleeping 60 seconds.
     */
    /* default */ static LongSupplier epochSecondSource = defaultEpochSecondSource();

    // Sentinel 0, not Long.MIN_VALUE: `now - last` below would overflow and wrap to a spuriously
    // small (even negative) value on the very first call, since `now` is real epoch seconds
    // (~1.7e9) and Long.MIN_VALUE is ~-9.2e18 — their difference exceeds Long.MAX_VALUE. Epoch
    // seconds are always positive, so 0 is a safe "never warned before" sentinel with no overflow.
    private static final AtomicLong LAST_WARN_EPOCH_SECOND = new AtomicLong(0L);
    private static final AtomicLong SUPPRESSED_SINCE_LAST_WARN = new AtomicLong(0);

    private MetricsSafety() {
        throw new AssertionError("No instances");
    }

    /**
     * Runs {@code recording} (a metric lookup and tag computation), catching and throttled-logging
     * any {@link Exception} — never rethrown, never allowed to affect the caller.
     */
    public static void runSafely(final Runnable recording) {
        try {
            recording.run();
        } catch (final Exception e) {
            warnThrottled("metric recording failed", e);
        }
    }

    public static void warnThrottled(final String message) {
        warnThrottledInternal(message, null);
    }

    public static void warnThrottled(final String message, final Exception cause) {
        warnThrottledInternal(message, cause);
    }

    private static void warnThrottledInternal(final String message, final Exception cause) {
        final long now = epochSecondSource.getAsLong();
        final long last = LAST_WARN_EPOCH_SECOND.get();
        if (now - last < WARN_WINDOW_SECONDS || !LAST_WARN_EPOCH_SECOND.compareAndSet(last, now)) {
            SUPPRESSED_SINCE_LAST_WARN.incrementAndGet();
            return;
        }
        final long suppressed = SUPPRESSED_SINCE_LAST_WARN.getAndSet(0);
        if (cause != null) {
            log.warn("{} (suppressed {} similar failures in the preceding {}s)",
                    message, suppressed, WARN_WINDOW_SECONDS, cause);
        } else {
            log.warn("{} (suppressed {} similar failures in the preceding {}s)",
                    message, suppressed, WARN_WINDOW_SECONDS);
        }
    }

    private static LongSupplier defaultEpochSecondSource() {
        return () -> System.currentTimeMillis() / 1000L;
    }

    /** Test-only: resets all global throttle state and the time source to the real clock. */
    /* default */ static void resetForTesting() {
        epochSecondSource = defaultEpochSecondSource();
        LAST_WARN_EPOCH_SECOND.set(0L);
        SUPPRESSED_SINCE_LAST_WARN.set(0);
    }
}
