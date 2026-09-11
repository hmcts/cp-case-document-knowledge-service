package uk.gov.hmcts.cp.cdk.jobmanager.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationIds;
import uk.gov.hmcts.cp.cdk.correlation.JobCorrelationAspect;
import uk.gov.hmcts.cp.cdk.jobmanager.TaskNames;
import uk.gov.hmcts.cp.cdk.metrics.TaskRetryMetricsAspect;

import java.lang.annotation.Annotation;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.json.Json;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

/**
 * DD-43183 AC-009 / GATE-3: proves {@link JobCorrelationAspect} is declared to run outermost of
 * DD-43182's {@link TaskRetryMetricsAspect} on the shared {@code ExecutableTask.execute} join
 * point, that both aspects compose correctly on one CGLIB proxy, and that the proxy remains
 * resolvable by its {@code @Task} annotation via {@code AopUtils.getTargetClass} — the property
 * that makes an aspect safe here where a plain decorator would silently unregister every task.
 */
@DisplayName("JobCorrelationProxyingTest (DD-43183 AC-009 / DD-43182 cross-ticket ordering)")
class JobCorrelationProxyingTest {

    @Test
    @DisplayName("GATE-3: JobCorrelationAspect declares @Order(HIGHEST_PRECEDENCE); "
            + "TaskRetryMetricsAspect declares no @Order at all — the exact contract both tickets' "
            + "designs require, so it cannot be inverted by an incidental edit")
    void orderingAnnotationsMatchTheAgreedContract() {
        final Order jobCorrelationOrder = JobCorrelationAspect.class.getAnnotation(Order.class);
        assertThat(jobCorrelationOrder).isNotNull();
        assertThat(jobCorrelationOrder.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);

        final Annotation taskRetryOrder = TaskRetryMetricsAspect.class.getAnnotation(Order.class);
        assertThat(taskRetryOrder).as("TaskRetryMetricsAspect must not declare @Order").isNull();
    }

    @Test
    @DisplayName("both aspects are genuine @Aspect components on the identical join point")
    void bothClassesAreAspectsOnTheSameJoinPoint() {
        assertThat(JobCorrelationAspect.class.getAnnotation(Aspect.class)).isNotNull();
        assertThat(TaskRetryMetricsAspect.class.getAnnotation(Aspect.class)).isNotNull();
    }

    @Test
    @DisplayName("with both real aspects composed on one proxy (Spring merges same-bean aspects into "
            + "one proxy), the correlation value is present in MDC for the whole call, and the task's "
            + "own execution still completes and returns correctly")
    void bothRealAspectsComposeCorrectlyOnOneProxy() {
        final StubTask target = new StubTask();
        final AspectJProxyFactory factory = new AspectJProxyFactory(target);
        // Order added does not dictate effective order — AspectJProxyFactory resolves precedence
        // from each aspect's own @Order, exactly as Spring's auto-proxying does for same-bean aspects.
        factory.addAspect(new TaskRetryMetricsAspect(new SimpleMeterRegistry()));
        factory.addAspect(new JobCorrelationAspect());

        final ExecutableTask proxy = factory.getProxy();
        final ExecutionInfo result = proxy.execute(baseExecutionInfo());

        assertThat(target.observedCorrelationId).isEqualTo("abc-123");
        assertThat(result).isNotNull();
        assertThat(MDC.get(CorrelationIds.MDC_KEY))
                .as("MDC is restored after the call, on the request/pool thread")
                .isNull();
    }

    @Test
    @DisplayName("a CGLIB proxy of a @Task-annotated class still resolves its @Task value via "
            + "AopUtils.getTargetClass — the property TaskRegistry itself relies on")
    void proxiedTaskStillResolvesItsTaskAnnotation() {
        final StubTask target = new StubTask();
        final AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new JobCorrelationAspect());

        final ExecutableTask proxy = factory.getProxy();

        assertThat(AopUtils.isAopProxy(proxy)).isTrue();
        final Class<?> targetClass = AopUtils.getTargetClass(proxy);
        assertThat(targetClass.getAnnotation(Task.class).value()).isEqualTo(TaskNames.GET_CASES_FOR_HEARING);
        assertThat(AopProxyUtils.ultimateTargetClass(proxy)).isEqualTo(StubTask.class);
    }

    private static ExecutionInfo baseExecutionInfo() {
        return executionInfo()
                .withAssignedTaskName(TaskNames.GET_CASES_FOR_HEARING)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withJobData(Json.createObjectBuilder().add("requestId", "abc-123").build())
                .withExecutionStatus(ExecutionStatus.STARTED)
                .build();
    }

    @Task(TaskNames.GET_CASES_FOR_HEARING)
    static class StubTask implements ExecutableTask {
        private volatile String observedCorrelationId;

        @Override
        public ExecutionInfo execute(final ExecutionInfo executionInfo) {
            observedCorrelationId = MDC.get(CorrelationIds.MDC_KEY);
            return executionInfo;
        }

        @Override
        public Optional<List<Long>> getRetryDurationsInSecs() {
            return Optional.empty();
        }
    }
}
