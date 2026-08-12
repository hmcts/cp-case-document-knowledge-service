package uk.gov.hmcts.cp.cdk.config;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * Captures MDC on the submitting (request) thread at decorate-time -- before
 * RequestContextFilter's finally block clears it -- and re-installs it on the worker
 * thread, so a manual discovery run's start/finish log lines still carry the request's
 * correlationId (Story 4, DD-43063).
 */
@Component
public class MdcCopyingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(final Runnable runnable) {
        final Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
