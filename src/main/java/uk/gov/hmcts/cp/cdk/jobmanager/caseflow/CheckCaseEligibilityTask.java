package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.services.CaseEligibilityService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JobManager task for {@code CHECK_CASE_ELIGIBILITY}.
 *
 * <p>Delegates the eligibility rule to {@link CaseEligibilityService}, which is also called
 * directly by {@code IngestionProcessorByCaseService} (the synchronous manual "Process IDPC" flow)
 * — the rule is defined once and reused by both entry points.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_CASE_ELIGIBILITY)
public class CheckCaseEligibilityTask implements ExecutableTask {

    private final ExecutionService executionService;
    private final CaseEligibilityService caseEligibilityService;
    private final JobManagerRetryProperties retryProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final String caseIdStr = jobData.getString(CTX_CASE_ID_KEY, null);
        final String cppuid = jobData.getString(CPPUID, null);

        if (caseIdStr == null || cppuid == null) {
            log.warn("Missing caseId or cppuid, skipping eligibility check");
            return complete(executionInfo);
        }

        final UUID caseId = UUID.fromString(caseIdStr);

        try {
            final Optional<ProsecutionCaseEligibilityInfo> eligibilityInfo =
                    caseEligibilityService.resolveEligibleCase(caseId, cppuid);

            if (eligibilityInfo.isEmpty()) {
                return complete(executionInfo);
            }

            log.info("Case {} is eligible. Proceeding to {}.", caseId, CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS);

            final JsonObject updatedJobData =
                    caseEligibilityService.withDefendantContext(jobData, eligibilityInfo.get());

            final ExecutionInfo executionInfoNew = executionInfo()
                    .from(executionInfo)
                    .withAssignedTaskName(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
                    .withJobData(updatedJobData)
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();

            executionService.executeWith(executionInfoNew);

        } catch (final Exception exception) {
            log.error("{} failed for caseId={} ", CHECK_CASE_ELIGIBILITY, caseIdStr, exception);

            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }

        return complete(executionInfo);
    }

    private ExecutionInfo complete(final ExecutionInfo executionInfo) {
        return executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        final JobManagerRetryProperties.RetryConfig retry = retryProperties.getDefaultRetry();
        return Optional.of(
                IntStream.range(0, retry.getMaxAttempts())
                        .mapToLong(i -> retry.getDelaySeconds())
                        .boxed()
                        .toList()
        );
    }
}
