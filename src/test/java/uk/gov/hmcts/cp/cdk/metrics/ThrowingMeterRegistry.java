package uk.gov.hmcts.cp.cdk.metrics;

import java.lang.reflect.Proxy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * DD-43182 Story 7 AC-005: a {@link io.micrometer.core.instrument.MeterRegistry} whose counters and
 * timers throw on every recording call, used to prove a metric-recording failure never changes a
 * business operation's outcome. Pre-registration at construction succeeds normally — this simulates
 * a registry backend hiccup at record-time, not a bean-wiring failure, matching exactly the failure
 * shape {@link MetricsSafety#runSafely(Runnable)} exists to contain.
 */
public final class ThrowingMeterRegistry extends SimpleMeterRegistry {

    @Override
    protected Counter newCounter(final Meter.Id id) {
        final Counter real = super.newCounter(id);
        return (Counter) Proxy.newProxyInstance(
                Counter.class.getClassLoader(),
                new Class<?>[]{Counter.class},
                (proxy, method, args) -> {
                    if ("increment".equals(method.getName())) {
                        throw new IllegalStateException("simulated registry failure");
                    }
                    return method.invoke(real, args);
                });
    }

    @Override
    protected Timer newTimer(final Meter.Id id, final DistributionStatisticConfig distributionStatisticConfig,
                             final PauseDetector pauseDetector) {
        final Timer real = super.newTimer(id, distributionStatisticConfig, pauseDetector);
        return (Timer) Proxy.newProxyInstance(
                Timer.class.getClassLoader(),
                new Class<?>[]{Timer.class},
                (proxy, method, args) -> {
                    if ("record".equals(method.getName())) {
                        throw new IllegalStateException("simulated registry failure");
                    }
                    return method.invoke(real, args);
                });
    }
}
