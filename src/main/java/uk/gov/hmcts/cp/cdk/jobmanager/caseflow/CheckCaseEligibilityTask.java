package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_COUNT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
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
import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_CASE_ELIGIBILITY)
public class CheckCaseEligibilityTask implements ExecutableTask {

    private final ExecutionService executionService;
    private final ProgressionClient progressionClient;
    private final JobManagerRetryProperties retryProperties;
    private final IngestionProperties ingestionProperties;

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
                    progressionClient.getProsecutionCaseEligibilityInfo(caseId, cppuid);

            if (eligibilityInfo.isEmpty()) {
                log.info("No prosecution case data found for caseId={}, skipping eligibility", caseId);
                return complete(executionInfo);
            }

            final ProsecutionCaseEligibilityInfo info = eligibilityInfo.get();
            final int defendantCount = info.defendantCount();
            final boolean isUseMultiDefendant = ingestionProperties.getFeature().isUseMultiDefendant();

            if (defendantCount < 1
                    || (defendantCount > 1 && !isUseMultiDefendant)) {

                log.info("Case {} has {} defendants. Not eligible to proceed. Completing task.", caseId, defendantCount);
                return complete(executionInfo);
            }

            final String checkIdpcTask = isUseMultiDefendant
                    ? CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS
                    : CHECK_IDPC_AVAILABILITY;

            log.info("Case {} has exactly 1 defendant. Proceeding to {}.", caseId, checkIdpcTask);

            final JsonObjectBuilder updatedJobData = createObjectBuilder(jobData);
            updatedJobData.add(CTX_DEFENDANT_ID_KEY, info.defendantIds().getFirst());
            updatedJobData.add(CTX_DEFENDANT_COUNT, info.defendantCount());

            final ExecutionInfo executionInfoNew = executionInfo()
                    .from(executionInfo)
                    .withAssignedTaskName(checkIdpcTask)
                    .withJobData(updatedJobData.build())
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
