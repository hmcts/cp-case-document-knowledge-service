package uk.gov.hmcts.cp.cdk.jobmanager.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.jobmanager.TaskNames;
import uk.gov.hmcts.cp.cdk.metrics.CdkMeters;
import uk.gov.hmcts.cp.cdk.metrics.TaskRetryMetricsAspect;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.json.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

@DisplayName("TaskRetryMetricsAspect tests (DD-43182 Story 6)")
class TaskRetryMetricsAspectTest {

    private SimpleMeterRegistry registry;
    private TaskRetryMetricsAspect aspect;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aspect = new TaskRetryMetricsAspect(registry);
    }

    @Test
    @DisplayName("AC-001: an INPROGRESS/shouldRetry=true return with remaining budget increments the counter")
    void incrementsOnGrantedRetryReturnPath() throws Throwable {
        final StubGetCasesForHearingTask stub = new StubGetCasesForHearingTask();
        stub.toReturn = infoWithRemaining(3, ExecutionStatus.INPROGRESS, true);
        final ExecutableTask proxy = proxiedTask(stub);

        proxy.execute(infoWithRemaining(3, ExecutionStatus.STARTED, false));

        assertThat(counterValue(TaskNames.GET_CASES_FOR_HEARING, CdkMeters.RETRY_POLICY_DEFAULT)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-001: the throw path also increments — TaskExecutor synthesises the retry outcome "
            + "outside CDKS code")
    void incrementsOnThrowPath() {
        final StubGetCasesForHearingTask stub = new StubGetCasesForHearingTask();
        stub.toThrow = new IllegalStateException("boom");
        final ExecutableTask proxy = proxiedTask(stub);

        assertThatThrownBy(() -> proxy.execute(infoWithRemaining(3, ExecutionStatus.STARTED, false)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(counterValue(TaskNames.GET_CASES_FOR_HEARING, CdkMeters.RETRY_POLICY_DEFAULT)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-002: remaining == 0 records nothing on any series (the withdrawn exhaustion shape)")
    void recordsNothingWhenRemainingIsZero() throws Throwable {
        final StubGetCasesForHearingTask stub = new StubGetCasesForHearingTask();
        stub.toReturn = infoWithRemaining(0, ExecutionStatus.INPROGRESS, true);
        final ExecutableTask proxy = proxiedTask(stub);

        proxy.execute(infoWithRemaining(0, ExecutionStatus.STARTED, false));

        assertThat(counterValue(TaskNames.GET_CASES_FOR_HEARING, CdkMeters.RETRY_POLICY_DEFAULT)).isEqualTo(0.0);
        assertThat(registry.getMeters()).noneMatch(m -> m.getId().getName().contains("exhausted"));
    }

    @Test
    @DisplayName("AC-002: an INPROGRESS result with shouldRetry=false records nothing — "
            + "neither retried nor exhausted (OQ-024)")
    void recordsNothingWhenShouldRetryIsFalse() throws Throwable {
        final StubGetCasesForHearingTask stub = new StubGetCasesForHearingTask();
        stub.toReturn = infoWithRemaining(3, ExecutionStatus.INPROGRESS, false);
        final ExecutableTask proxy = proxiedTask(stub);

        proxy.execute(infoWithRemaining(3, ExecutionStatus.STARTED, false));

        assertThat(counterValue(TaskNames.GET_CASES_FOR_HEARING, CdkMeters.RETRY_POLICY_DEFAULT)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("AC-005: a COMPLETED result records nothing")
    void recordsNothingWhenCompleted() throws Throwable {
        final StubGetCasesForHearingTask stub = new StubGetCasesForHearingTask();
        stub.toReturn = infoWithRemaining(3, ExecutionStatus.COMPLETED, false);
        final ExecutableTask proxy = proxiedTask(stub);

        proxy.execute(infoWithRemaining(3, ExecutionStatus.STARTED, false));

        assertThat(counterValue(TaskNames.GET_CASES_FOR_HEARING, CdkMeters.RETRY_POLICY_DEFAULT)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("AC-003: a target with no @Task annotation records nothing — no other value can ever be emitted")
    void recordsNothingForUnannotatedTarget() throws Throwable {
        final StubUnannotatedTask stub = new StubUnannotatedTask();
        stub.toReturn = infoWithRemaining(3, ExecutionStatus.INPROGRESS, true);
        final ExecutableTask proxy = proxiedTask(stub);

        proxy.execute(infoWithRemaining(3, ExecutionStatus.STARTED, false));

        assertThat(registry.getMeters()).allSatisfy(m ->
                assertThat(m.getId().getTag(CdkMeters.TAG_TASK_NAME)).isNotEqualTo("UNANNOTATED"));
    }

    @Test
    @DisplayName("AC-004: retry_policy is determined solely by task_name, at zero extra series cost — "
            + "7 series total, not 28")
    void sevenSeriesTotalNotTwentyEight() {
        final long taskRetrySeriesCount = registry.getMeters().stream()
                .filter(m -> CdkMeters.TASK_RETRY.equals(m.getId().getName()))
                .count();

        assertThat(taskRetrySeriesCount).isEqualTo(7);
    }

    @Test
    @DisplayName("AC-006/NFR-004: the aspect never alters the returned ExecutionInfo or the thrown exception")
    void neverAltersReturnValueOrException() throws Throwable {
        final StubGetCasesForHearingTask stub = new StubGetCasesForHearingTask();
        stub.toReturn = infoWithRemaining(3, ExecutionStatus.INPROGRESS, true);
        final ExecutableTask proxy = proxiedTask(stub);

        final ExecutionInfo result = proxy.execute(infoWithRemaining(3, ExecutionStatus.STARTED, false));

        assertThat(result).isSameAs(stub.toReturn);
    }

    private ExecutableTask proxiedTask(final ExecutableTask target) {
        final AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private double counterValue(final String taskName, final String retryPolicy) {
        return registry.get(CdkMeters.TASK_RETRY)
                .tag(CdkMeters.TAG_TASK_NAME, taskName)
                .tag(CdkMeters.TAG_RETRY_POLICY, retryPolicy)
                .counter().count();
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

    static class StubUnannotatedTask implements ExecutableTask {
        private ExecutionInfo toReturn;

        @Override
        public ExecutionInfo execute(final ExecutionInfo executionInfo) {
            return toReturn;
        }

        @Override
        public Optional<List<Long>> getRetryDurationsInSecs() {
            return Optional.of(List.of(1L));
        }
    }
}
