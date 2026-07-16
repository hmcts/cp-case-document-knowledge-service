package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;


import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.REQUEST_ID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuid;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.services.IdpcAvailabilityService;
import uk.gov.hmcts.cp.cdk.services.NewIdpcDocument;
import uk.gov.hmcts.cp.cdk.services.RetrieveMaterialAndUploadJobDataService;
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
 * JobManager task for {@code CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS}.
 *
 * <p>Delegates the IDPC-availability rule to {@link IdpcAvailabilityService} (also called directly
 * by {@code IngestionProcessorByCaseService}, the synchronous manual "Process IDPC" flow — the rule
 * is defined once and reused by both entry points), builds the per-document job data via the shared
 * {@link RetrieveMaterialAndUploadJobDataService}, and dispatches {@code RETRIEVE_MATERIAL_AND_UPLOAD} for every
 * document. The {@code ExecutionInfo}/dispatch shape stays specific to this task — only the job-data
 * building is shared with {@code IngestionProcessorByCaseService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
public class CheckIdpcAvailabilityAllDefendantsTask implements ExecutableTask {

    private final IdpcAvailabilityService idpcAvailabilityService;
    private final RetrieveMaterialAndUploadJobDataService retrievalJobDataService;
    private final ExecutionService executionService;
    private final JobManagerRetryProperties retryProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final String caseIdString = jobData.getString(CTX_CASE_ID_KEY, null);
        final String cppuid = jobData.getString(CPPUID, null);
        final String requestId = jobData.getString(REQUEST_ID, "unknown");

        try {
            final UUID caseId = parseUuid(caseIdString).get();

            final List<NewIdpcDocument> newDocuments =
                    idpcAvailabilityService.retrieveDocuments(caseId, cppuid);

            dispatchRetrievalTasks(executionInfo, jobData, newDocuments);

            return executionInfo().from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (Exception ex) {
            log.error("{} failed. caseId={}, requestId={}", CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS,
                    caseIdString, requestId, ex);

            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }
    }

    private void dispatchRetrievalTasks(final ExecutionInfo executionInfo,
                                        final JsonObject jobData,
                                        final List<NewIdpcDocument> newDocuments) {
        for (final JsonObject updatedJobData : retrievalJobDataService.enrich(jobData, newDocuments)) {
            final ExecutionInfo executionInfoNew = executionInfo()
                    .from(executionInfo)
                    .withAssignedTaskName(RETRIEVE_MATERIAL_AND_UPLOAD)
                    .withJobData(updatedJobData)
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();

            executionService.executeWith(executionInfoNew);
        }
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
