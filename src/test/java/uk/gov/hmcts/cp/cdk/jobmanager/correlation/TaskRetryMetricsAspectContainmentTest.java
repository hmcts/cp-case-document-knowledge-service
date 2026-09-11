package uk.gov.hmcts.cp.cdk.jobmanager.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.jobmanager.TaskNames;
import uk.gov.hmcts.cp.cdk.metrics.ThrowingMeterRegistry;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import uk.gov.hmcts.cp.cdk.metrics.TaskRetryMetricsAspect;

/**
 * DD-43182 Story 7 AC-005 (fourth business-path shape): a JobManager task execution completes with
 * the exact same {@link ExecutionInfo} (or propagated exception) even when the retry-metrics
 * registry itself throws while recording. Kept in this package deliberately — the aspect's pointcut
 * is scoped to {@code uk.gov.hmcts.cp.cdk.jobmanager..*}, so a stub task outside this package would
 * silently never be advised.
 */
@DisplayName("TaskRetryMetricsAspect containment under a throwing registry (DD-43182 Story 7, AC-005)")
class TaskRetryMetricsAspectContainmentTest {

    @Test
    @DisplayName("a granted-retry return is passed through unchanged even when the registry throws")
    void taskExecutionReturnValueIsUnaffectedByThrowingRegistry() throws Throwable {
        final TaskRetryMetricsAspect aspect = new TaskRetryMetricsAspect(new ThrowingMeterRegistry());
        final StubGetCasesForHearingTask stub = new StubGetCasesForHearingTask();
        stub.toReturn = infoWithRemaining(3, ExecutionStatus.INPROGRESS, true);
        final ExecutableTask proxy = proxiedTask(stub, aspect);

        final ExecutionInfo result = proxy.execute(infoWithRemaining(3, ExecutionStatus.STARTED, false));

        assertThat(result).isSameAs(stub.toReturn);
    }

    @Test
    @DisplayName("the task's own thrown exception still propagates unchanged even when the registry throws")
    void taskExecutionExceptionIsUnaffectedByThrowingRegistry() {
        final TaskRetryMetricsAspect aspect = new TaskRetryMetricsAspect(new ThrowingMeterRegistry());
        final StubGetCasesForHearingTask stub = new StubGetCasesForHearingTask();
        final IllegalStateException businessFailure = new IllegalStateException("boom");
        stub.toThrow = businessFailure;
        final ExecutableTask proxy = proxiedTask(stub, aspect);

        assertThatThrownBy(() -> proxy.execute(infoWithRemaining(3, ExecutionStatus.STARTED, false)))
                .isSameAs(businessFailure);
    }

    @Test
    @DisplayName("construction of the aspect itself against a throwing registry never throws — "
            + "pre-registration succeeds normally, only the runtime recording call fails")
    void aspectConstructionAgainstThrowingRegistryNeverThrows() {
        assertThatCode(() -> new TaskRetryMetricsAspect(new ThrowingMeterRegistry())).doesNotThrowAnyException();
    }

    private ExecutableTask proxiedTask(final ExecutableTask target, final TaskRetryMetricsAspect aspect) {
        final AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private static ExecutionInfo infoWithRemaining(final int remaining, final ExecutionStatus status,
                                                    final boolean shouldRetry) {
        return executionInfo()
                .withAssignedTaskName("STUB")
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withJobData(Json.createObjectBuilder().build())
                .withExecutionStatus(status)
                .withShouldRetry(shouldRetry)
                .withRetryAttemptsRemaining(remaining)
                .build();
    }

    @Task(TaskNames.GET_CASES_FOR_HEARING)
    static class StubGetCasesForHearingTask implements ExecutableTask {
        private ExecutionInfo toReturn;
        private RuntimeException toThrow;

        @Override
        public ExecutionInfo execute(final ExecutionInfo executionInfo) {
            if (toThrow != null) {
                throw toThrow;
            }
            return toReturn;
        }

        @Override
        public Optional<List<Long>> getRetryDurationsInSecs() {
            return Optional.of(List.of(1L, 2L, 3L));
        }
    }
}
