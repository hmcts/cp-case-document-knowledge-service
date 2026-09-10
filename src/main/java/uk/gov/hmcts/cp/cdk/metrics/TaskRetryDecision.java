package uk.gov.hmcts.cp.cdk.metrics;

import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;

/**
 * Replicates {@code task-manager-service}'s internal retry-grant predicate ({@code
 * TaskExecutor.canRetry}) from inputs a task already receives (DD-43182 Story 6, ADR-006):
 * {@code ExecutionInfo.getRetryAttemptsRemaining()} plus the task's own configured
 * {@code getRetryDurationsInSecs()} budget size.
 *
 * <p><strong>Takes no {@code shouldRetry} input</strong> (OQ-023) — the caller tests {@code
 * shouldRetry} separately, because this predicate is also used from the exception-throw path,
 * where no {@code ExecutionInfo} carrying a {@code shouldRetry} value yet exists on the caller's
 * side; the library synthesises that outcome itself.
 *
 * <p>This is a replica of library behaviour, not a call into the library — the one genuine
 * implementation liability this story accepts (ADR-006). {@code TaskRetryHttpLiveTest} ties the
 * CDKS-side prediction to the library's real behaviour so a library bump that changes {@code
 * canRetry} fails CI rather than silently drifting.
 */
public final class TaskRetryDecision {

    private TaskRetryDecision() {
        throw new AssertionError("No instances");
    }

    public static boolean willBeRetried(final ExecutionInfo executionInfo, final ExecutableTask task) {
        final Integer remaining = executionInfo.getRetryAttemptsRemaining();
        return remaining != null && remaining > 0 && task.getRetryDurationsInSecs().isPresent();
    }
}
