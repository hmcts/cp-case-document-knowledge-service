package uk.gov.hmcts.cp.cdk.correlation;

import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Defence in depth (DD-43183 Story 3, AC-008): clears MDC after every job on the
 * {@code jobExecutorThreadPool} pool, so that even a future task writing MDC outside {@link
 * JobCorrelationAspect}'s scope, or a library change that logs after {@code execute(...)} returns,
 * cannot leak onto the next unrelated job on the same pooled thread.
 *
 * <p>Sets the decorator via {@code postProcessBeforeInitialization} rather than replacing the bean
 * outright — {@code jobExecutorThreadPool} is {@code @ConditionalOnMissingBean}, and replacing it
 * would mean copying the library's own {@code @Value}-bound pool defaults into CDKS with no
 * mechanism to keep them in step across a library bump.
 */
@Component
public class JobExecutorMdcBeanPostProcessor implements BeanPostProcessor {

    private static final String JOB_EXECUTOR_BEAN_NAME = "jobExecutorThreadPool";

    @Override
    public Object postProcessBeforeInitialization(final Object bean, final String beanName) throws BeansException {
        if (JOB_EXECUTOR_BEAN_NAME.equals(beanName) && bean instanceof ThreadPoolTaskExecutor executor) {
            executor.setTaskDecorator(mdcClearingDecorator());
        }
        return bean;
    }

    private static TaskDecorator mdcClearingDecorator() {
        return runnable -> () -> {
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
