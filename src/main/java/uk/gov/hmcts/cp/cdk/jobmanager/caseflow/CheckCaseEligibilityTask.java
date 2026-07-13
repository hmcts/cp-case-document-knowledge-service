package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_COUNT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SYNCHRONOUS_INVOCATION_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JobManager task for {@code CHECK_CASE_ELIGIBILITY}.
 *
 * <p>{@code IngestionProcessorByCaseService} (the synchronous manual "Process IDPC" flow) invokes
 * {@link #execute(ExecutionInfo)} directly, in-process, marking its {@link ExecutionInfo}'s job data
 * with {@link uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys#CTX_SYNCHRONOUS_INVOCATION_KEY}.
 * When that flag is set and the case is eligible, {@code execute} returns the follow-on
 * {@code CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS} {@link ExecutionInfo} instead of dispatching it via
 * {@link ExecutionService#executeWith(ExecutionInfo)} — the synchronous caller invokes the next task
 * itself, so dispatching it here as well would process the same case twice. For the scheduled/async
 * flow (JobExecutor invoking a queued job, no flag set) behaviour is unchanged: this task dispatches
 * the next step itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_CASE_ELIGIBILITY)
public class CheckCaseEligibilityTask implements ExecutableTask {

    public static final int SINGLE_DEFENDANT_COUNT = 1;

    private final ExecutionService executionService;
    private final ProgressionClient progressionClient;
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
                    resolveEligibleCase(caseId, cppuid);

            if (eligibilityInfo.isEmpty()) {
                return complete(executionInfo);
            }

            log.info("Case {} is eligible. Proceeding to {}.", caseId, CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS);

            final JsonObject updatedJobData =
                    withDefendantContext(jobData, eligibilityInfo.get());

            final ExecutionInfo executionInfoNew = executionInfo()
                    .from(executionInfo)
                    .withAssignedTaskName(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
                    .withJobData(updatedJobData)
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();

            if (jobData.getBoolean(CTX_SYNCHRONOUS_INVOCATION_KEY, false)) {
                return executionInfoNew;
            }

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

    /**
     * Resolves prosecution-case eligibility for the given case.
     *
     * @return the eligibility info only when the case exists and has at least one defendant;
     *         otherwise {@link Optional#empty()} (not eligible / nothing to ingest).
     */
    /* default */ Optional<ProsecutionCaseEligibilityInfo> resolveEligibleCase(final UUID caseId, final String cppuid) {
        if (caseId == null || cppuid == null) {
            log.warn("Missing caseId or cppuid, skipping eligibility check");
            return Optional.empty();
        }

        final Optional<ProsecutionCaseEligibilityInfo> eligibilityInfo =
                progressionClient.getProsecutionCaseEligibilityInfo(caseId, cppuid);

        if (eligibilityInfo.isEmpty()) {
            log.info("No prosecution case data found for caseId={}, not eligible", caseId);
            return Optional.empty();
        }

        final ProsecutionCaseEligibilityInfo info = eligibilityInfo.get();
        if (info.defendantCount() < SINGLE_DEFENDANT_COUNT) {
            log.info("Case {} has no defendants. Not eligible to proceed.", caseId);
            return Optional.empty();
        }

        log.info("Case {} has {} defendants and is eligible for ingestion.", caseId, info.defendantCount());
        return eligibilityInfo;
    }

    /**
     * Enriches the supplied job data with the defendant context required by the downstream
     * IDPC-availability step.
     */
    /* default */ JsonObject withDefendantContext(final JsonObject jobData, final ProsecutionCaseEligibilityInfo info) {
        return createObjectBuilder(jobData)
                .add(CTX_DEFENDANT_ID_KEY, info.defendantIds().getFirst())
                .add(CTX_DEFENDANT_COUNT, info.defendantCount())
                .build();
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
