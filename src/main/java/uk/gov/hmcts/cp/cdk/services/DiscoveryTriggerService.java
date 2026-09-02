package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryOperation;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Dispatches an on-demand discovery run (Story 3, DD-43062) by reusing DiscoveryService's
 * existing runIntradayDiscovery/runNightlyDiscovery unchanged, off the request thread.
 * A separate class rather than a new DiscoveryService method: DiscoveryService's constructor
 * already takes 9 dependencies, so adding a TaskExecutor there would touch every
 * DiscoveryServiceTest construction site.
 * trigger/discoveryOperation MDC tagging (Story 4, DD-43063) relies on MdcCopyingTaskDecorator
 * having already copied the request's correlationId onto this worker thread's MDC.
 */
@Slf4j
@Service
public class DiscoveryTriggerService {

    private final DiscoveryService discoveryService;
    private final TaskExecutor discoveryTriggerExecutor;

    public DiscoveryTriggerService(final DiscoveryService discoveryService,
                                    @Qualifier("discoveryTriggerExecutor") final TaskExecutor discoveryTriggerExecutor) {
        this.discoveryService = discoveryService;
        this.discoveryTriggerExecutor = discoveryTriggerExecutor;
    }

    public void trigger(final DiscoveryOperation operation) {
        // Exhaustive switch expression, no default: a future third enum value fails to compile here.
        final Runnable delegate = switch (operation) {
            case INTRADAY -> discoveryService::runIntradayDiscovery;
            case NIGHTLY -> discoveryService::runNightlyDiscovery;
        };
        discoveryTriggerExecutor.execute(() -> runWithLogging(operation, delegate));
    }

    private void runWithLogging(final DiscoveryOperation operation, final Runnable delegate) {
        final long startedAt = System.currentTimeMillis();
        MDC.put("trigger", "manual");
        MDC.put("discoveryOperation", operation.toString());
        try {
            log.info("Manual discovery run starting discoveryOperation={} trigger=manual", operation);
            delegate.run();
            log.info("Manual discovery run finished discoveryOperation={} trigger=manual durationMs={}",
                    operation, System.currentTimeMillis() - startedAt);
        } catch (final Exception e) {
            log.error("Manual discovery run failed discoveryOperation={} trigger=manual durationMs={}",
                    operation, System.currentTimeMillis() - startedAt, e);
        } finally {
            MDC.remove("trigger");
            MDC.remove("discoveryOperation");
        }
    }
}
