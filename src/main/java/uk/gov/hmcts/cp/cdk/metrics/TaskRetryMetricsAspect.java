package uk.gov.hmcts.cp.cdk.metrics;

import uk.gov.hmcts.cp.cdk.jobmanager.TaskNames;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Counts every JobManager retry that will actually be <em>granted</em> — not merely requested —
 * per task and per retry policy (DD-43182 Story 6, ADR-006).
 *
 * <p>Deliberately carries <strong>no {@code @Order}</strong> annotation: Spring AOP's default
 * (lowest precedence) leaves it inside DD-43183's {@code JobCorrelationAspect}
 * ({@code @Order(HIGHEST_PRECEDENCE)}) on the identical join point, so this aspect's own throttled
 * WARN log lines carry a correlation ID once that ticket ships. Do not add {@code @Order} "for
 * clarity" — that would invert the required ordering (GATE-3, cross-ticket coordination).
 *
 * <p>{@code cdk_task_retry_exhausted_total} is <strong>not</strong> built (ADR-011): a task
 * execution can never observe an exhausted budget — the library abandons the job between
 * executions, with no task run at all. See {@code CdkMeters}' Javadoc for the full reasoning.
 */
@Aspect
@Component
@ConditionalOnProperty(prefix = "cdk.metrics", name = "enabled", matchIfMissing = true)
public class TaskRetryMetricsAspect {

    private static final Map<String, String> RETRY_POLICY_BY_TASK_NAME = buildRetryPolicyByTaskName();

    private final Map<String, Counter> countersByTaskName;

    public TaskRetryMetricsAspect(final MeterRegistry registry) {
        this.countersByTaskName = new LinkedHashMap<>();
        RETRY_POLICY_BY_TASK_NAME.forEach((taskName, retryPolicy) ->
                countersByTaskName.put(taskName, Counter.builder(CdkMeters.TASK_RETRY)
                        .tag(CdkMeters.TAG_TASK_NAME, taskName)
                        .tag(CdkMeters.TAG_RETRY_POLICY, retryPolicy)
                        .register(registry)));
    }

    @Around("execution(* uk.gov.hmcts.cp.cdk.jobmanager..*.execute(uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo))")
    public Object aroundExecute(final ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        final ExecutionInfo input = (ExecutionInfo) proceedingJoinPoint.getArgs()[0];
        final ExecutableTask task = (ExecutableTask) proceedingJoinPoint.getTarget();

        try {
            final Object result = proceedingJoinPoint.proceed();
            final ExecutionInfo output = (ExecutionInfo) result;
            if (output.getExecutionStatus() == ExecutionStatus.INPROGRESS && output.isShouldRetry()) {
                recordIfGranted(input, task);
            }
            return result;
        } catch (final Exception exception) {
            // NOT Throwable (PMD errorprone.AvoidCatchingThrowable) — an Error propagates
            // unrecorded and uncounted, the same principle MetricsSafety applies to recording
            // failures. TaskExecutor synthesises the same INPROGRESS/shouldRetry=true outcome
            // outside CDKS code when a task throws an Exception — the throw path this story exists
            // to cover.
            recordIfGranted(input, task);
            throw exception;
        }
    }

    private void recordIfGranted(final ExecutionInfo input, final ExecutableTask task) {
        MetricsSafety.runSafely(() -> {
            if (!TaskRetryDecision.willBeRetried(input, task)) {
                return;
            }
            final String taskName = resolveKnownTaskName(task);
            if (taskName == null) {
                return;
            }
            final Counter counter = countersByTaskName.get(taskName);
            if (counter != null) {
                counter.increment();
            }
        });
    }

    private static String resolveKnownTaskName(final ExecutableTask task) {
        final Class<?> targetClass = AopUtils.getTargetClass(task);
        final Task annotation = targetClass.getAnnotation(Task.class);
        if (annotation == null) {
            return null;
        }
        final String name = annotation.value();
        return RETRY_POLICY_BY_TASK_NAME.containsKey(name) ? name : null;
    }

    private static Map<String, String> buildRetryPolicyByTaskName() {
        final Map<String, String> policies = new LinkedHashMap<>();
        policies.put(TaskNames.GET_CASES_FOR_HEARING, CdkMeters.RETRY_POLICY_DEFAULT);
        policies.put(TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS, CdkMeters.RETRY_POLICY_DEFAULT);
        policies.put(TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD, CdkMeters.RETRY_POLICY_DEFAULT);
        policies.put(TaskNames.CHECK_ALL_DOCUMENTS_INGESTION_STATUS, CdkMeters.RETRY_POLICY_VERIFY_DOCUMENT_STATUS);
        policies.put(TaskNames.CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS, CdkMeters.RETRY_POLICY_VERIFY_DOCUMENT_STATUS);
        policies.put(TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION, CdkMeters.RETRY_POLICY_QUESTIONS);
        policies.put(TaskNames.GENERATE_ANSWER_FOR_QUERY, CdkMeters.RETRY_POLICY_NONE);
        return policies;
    }
}
