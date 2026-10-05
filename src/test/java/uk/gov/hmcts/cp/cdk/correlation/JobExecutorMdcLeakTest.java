package uk.gov.hmcts.cp.cdk.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * DD-43183 Story 3, AC-008: with {@code jobExecutorThreadPool}'s pool size forced to 1, a job that
 * sets MDC and then returns — or throws — is followed by a second, unrelated job on the same
 * thread that observes nothing left over, for both the normal-return and the throwing path. This
 * is the guarantee that holds even if a future task, or the library itself, writes MDC outside
 * {@link JobCorrelationAspect}'s scope.
 */
@DisplayName("JobExecutorMdcLeakTest (DD-43183 Story 3, AC-008)")
class JobExecutorMdcLeakTest {

    private ThreadPoolTaskExecutor newPoolOfOneWithMdcClearingDecorator() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();

        new JobExecutorMdcBeanPostProcessor().postProcessBeforeInitialization(executor, "jobExecutorThreadPool");
        return executor;
    }

    @Test
    @DisplayName("a job that sets MDC and returns normally leaves nothing for the next job on the same thread")
    void noLeakAfterNormalReturn() throws InterruptedException {
        final ThreadPoolTaskExecutor executor = newPoolOfOneWithMdcClearingDecorator();
        final CountDownLatch jobADone = new CountDownLatch(1);
        final CountDownLatch jobBDone = new CountDownLatch(1);
        final AtomicReference<String> jobBObservedValue = new AtomicReference<>("not-run");

        executor.execute(() -> {
            MDC.put(CorrelationIds.MDC_KEY, "job-a-value");
            jobADone.countDown();
        });
        assertThat(jobADone.await(5, TimeUnit.SECONDS)).isTrue();

        executor.execute(() -> {
            jobBObservedValue.set(MDC.get(CorrelationIds.MDC_KEY));
            jobBDone.countDown();
        });
        assertThat(jobBDone.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(jobBObservedValue.get()).isNull();
        executor.shutdown();
    }

    @Test
    @DisplayName("a job that sets MDC and then throws still leaves nothing for the next job on the same thread")
    void noLeakAfterThrowingJob() throws InterruptedException {
        final ThreadPoolTaskExecutor executor = newPoolOfOneWithMdcClearingDecorator();
        final CountDownLatch jobADone = new CountDownLatch(1);
        final CountDownLatch jobBDone = new CountDownLatch(1);
        final AtomicReference<String> jobBObservedValue = new AtomicReference<>("not-run");

        executor.execute(() -> {
            try {
                MDC.put(CorrelationIds.MDC_KEY, "job-a-value");
                throw new IllegalStateException("job A failed");
            } finally {
                jobADone.countDown();
            }
        });
        assertThat(jobADone.await(5, TimeUnit.SECONDS)).isTrue();

        executor.execute(() -> {
            jobBObservedValue.set(MDC.get(CorrelationIds.MDC_KEY));
            jobBDone.countDown();
        });
        assertThat(jobBDone.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(jobBObservedValue.get()).isNull();
        executor.shutdown();
    }

    @Test
    @DisplayName("the post-processor only decorates the bean named jobExecutorThreadPool — "
            + "a differently-named executor is left undecorated, so MDC set by one job on it is "
            + "still visible to the next (the exact leak this post-processor exists to prevent, "
            + "proven absent here on purpose to show the name check is load-bearing)")
    void onlyDecoratesTheNamedBean() throws InterruptedException {
        final ThreadPoolTaskExecutor other = new ThreadPoolTaskExecutor();
        other.setCorePoolSize(1);
        other.setMaxPoolSize(1);
        other.initialize();

        final Object result = new JobExecutorMdcBeanPostProcessor()
                .postProcessBeforeInitialization(other, "someOtherExecutor");
        assertThat(result).isSameAs(other);

        final CountDownLatch jobADone = new CountDownLatch(1);
        final CountDownLatch jobBDone = new CountDownLatch(1);
        final AtomicReference<String> jobBObservedValue = new AtomicReference<>("not-run");

        other.execute(() -> {
            MDC.put(CorrelationIds.MDC_KEY, "leaked-value");
            jobADone.countDown();
        });
        assertThat(jobADone.await(5, TimeUnit.SECONDS)).isTrue();

        other.execute(() -> {
            jobBObservedValue.set(MDC.get(CorrelationIds.MDC_KEY));
            MDC.clear();
            jobBDone.countDown();
        });
        assertThat(jobBDone.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(jobBObservedValue.get())
                .as("undecorated executor: today's MDC.clear() in RequestContextFilter/JobCorrelationAspect "
                        + "is what actually prevents this in production — this bean alone has no such guard")
                .isEqualTo("leaked-value");
        other.shutdown();
    }
}
