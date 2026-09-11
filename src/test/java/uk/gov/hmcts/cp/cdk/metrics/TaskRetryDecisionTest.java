package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;

@DisplayName("TaskRetryDecision tests (DD-43182 Story 6, replicated canRetry predicate)")
class TaskRetryDecisionTest {

    @Test
    @DisplayName("remaining > 0 and a configured retry budget: will be retried")
    void willBeRetriedWhenRemainingPositiveAndBudgetPresent() {
        final ExecutionInfo info = infoWithRemaining(3);
        final ExecutableTask task = taskWithBudget(List.of(1L, 2L, 3L));

        assertThat(TaskRetryDecision.willBeRetried(info, task)).isTrue();
    }

    @Test
    @DisplayName("remaining == 0: not retried — the exhaustion boundary")
    void notRetriedWhenRemainingIsZero() {
        final ExecutionInfo info = infoWithRemaining(0);
        final ExecutableTask task = taskWithBudget(List.of(1L, 2L, 3L));

        assertThat(TaskRetryDecision.willBeRetried(info, task)).isFalse();
    }

    @Test
    @DisplayName("remaining == null: not retried")
    void notRetriedWhenRemainingIsNull() {
        final ExecutionInfo info = infoWithRemaining(null);
        final ExecutableTask task = taskWithBudget(List.of(1L, 2L, 3L));

        assertThat(TaskRetryDecision.willBeRetried(info, task)).isFalse();
    }

    @Test
    @DisplayName("the GENERATE_ANSWER_FOR_QUERY shape — no getRetryDurationsInSecs() override at all "
            + "(empty Optional) — is never retried regardless of remaining")
    void notRetriedWhenTaskHasNoRetryBudgetConfigured() {
        final ExecutionInfo info = infoWithRemaining(5);
        final ExecutableTask task = taskWithBudget(null);

        assertThat(TaskRetryDecision.willBeRetried(info, task)).isFalse();
    }

    @Test
    @DisplayName("remaining == 1 (the final granted execution) is still eligible — "
            + "this is the row the exhaustion counter would have needed and cannot get")
    void remainingOneIsStillEligible() {
        final ExecutionInfo info = infoWithRemaining(1);
        final ExecutableTask task = taskWithBudget(List.of(1L));

        assertThat(TaskRetryDecision.willBeRetried(info, task)).isTrue();
    }

    @Test
    @DisplayName("OQ-023: the predicate takes no shouldRetry input — an INPROGRESS/shouldRetry=false "
            + "ExecutionInfo still evaluates purely on remaining/budget")
    void predicateIgnoresShouldRetryEntirely() {
        final ExecutionInfo info = executionInfo()
                .withAssignedTaskName("STUB")
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withJobData(Json.createObjectBuilder().build())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .withShouldRetry(false)
                .withRetryAttemptsRemaining(3)
                .build();
        final ExecutableTask task = taskWithBudget(List.of(1L, 2L, 3L));

        assertThat(TaskRetryDecision.willBeRetried(info, task)).isTrue();
    }

    private static ExecutionInfo infoWithRemaining(final Integer remaining) {
        final var builder = executionInfo()
                .withAssignedTaskName("STUB")
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withJobData(Json.createObjectBuilder().build())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .withShouldRetry(true);
        if (remaining != null) {
            builder.withRetryAttemptsRemaining(remaining);
        }
        return builder.build();
    }

    private static ExecutableTask taskWithBudget(final List<Long> budget) {
        return new ExecutableTask() {
            @Override
            public ExecutionInfo execute(final ExecutionInfo executionInfo) {
                return executionInfo;
            }

            @Override
            public Optional<List<Long>> getRetryDurationsInSecs() {
                return Optional.ofNullable(budget);
            }
        };
    }
}
