package uk.gov.hmcts.cp.cdk.correlation;

import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Restores the dispatching request's correlation ID (and, where present, {@code caseId}/{@code
 * docId}/{@code transactionId}) into MDC for the duration of every JobManager task execution
 * (DD-43183 Story 3, ADR-004) — one interception point covering all seven {@code @Task} beans and
 * any future eighth, rather than a try/finally repeated in each.
 *
 * <p><strong>Ordered outermost</strong> ({@link Ordered#HIGHEST_PRECEDENCE}) of DD-43182's
 * {@code TaskRetryMetricsAspect} on the identical join point, so that aspect's own throttled WARN
 * log lines carry a correlation ID (GATE-3, cross-ticket coordination — see both tickets' ADRs).
 *
 * <p>Not conditional on any property: a service that can be configured to stop correlating its own
 * logs has the bug this ticket closes. No {@code catch}, no swallow, no rewriting of the returned
 * {@code ExecutionInfo} — the return value and any thrown {@link Throwable} pass through untouched
 * (NFR-004).
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JobCorrelationAspect {

    @Around("execution(* uk.gov.hmcts.cp.cdk.jobmanager..*.execute(uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo))")
    @SuppressWarnings("PMD.UnusedLocalVariable") // the try-with-resources variable is used for its close()
    public Object aroundExecute(final ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        final ExecutionInfo executionInfo = (ExecutionInfo) proceedingJoinPoint.getArgs()[0];
        try (CorrelationScope scope = CorrelationScope.fromJobData(executionInfo.getJobData())) {
            return proceedingJoinPoint.proceed();
        }
    }
}
