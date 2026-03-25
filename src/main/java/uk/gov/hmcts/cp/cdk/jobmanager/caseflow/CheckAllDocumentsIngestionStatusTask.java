package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_ALL_DOCUMENTS_INGESTION_STATUS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GENERATE_ANSWER_FOR_QUERY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOCIDS_ARRAY;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
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


@Component
@RequiredArgsConstructor
@Task(CHECK_ALL_DOCUMENTS_INGESTION_STATUS)
@Slf4j
public class CheckAllDocumentsIngestionStatusTask implements ExecutableTask {

    private final DocumentIdResolver documentIdResolver;
    private final ExecutionService executionService;
    private final JobManagerRetryProperties retryProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final List<UUID> docIds = jobData.containsKey(CTX_DOCIDS_ARRAY)
                ? jobData.getJsonArray(CTX_DOCIDS_ARRAY).stream()
                .map(v -> UUID.fromString(v.toString().replace("\"", "")))
                .toList()
                : List.of();

        if (docIds.isEmpty()) {
            log.warn("No docIds found in context. Skipping processing.");
            return complete(executionInfo);
        }
        for (UUID docid : docIds) {
            Optional<String> ingestionPhaseOpt = documentIdResolver.findIngestionStatus(docid);

            if (ingestionPhaseOpt.isEmpty() || !DocumentIngestionPhase.INGESTED.toString().equals(ingestionPhaseOpt.get())) {
                log.info("Document {} not ingested yet → retrying as current status is {}", docid,ingestionPhaseOpt.get());
                return retry(executionInfo);
            }
        }
        log.info("All documents are INGESTED. Proceeding with query generation.");
        ExecutionInfo executionInfoNew = executionInfo()
                .from(executionInfo)
                .withAssignedTaskName(GENERATE_ANSWER_FOR_QUERY)
                .withJobData(jobData)
                .withExecutionStatus(ExecutionStatus.STARTED)
                .build();

        executionService.executeWith(executionInfoNew);

        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        var retry = retryProperties.getVerifyDocumentStatus();
        return Optional.of(
                IntStream.range(0, retry.getMaxAttempts())
                        .mapToLong(i -> retry.getDelaySeconds())
                        .boxed()
                        .toList()
        );
    }


    private ExecutionInfo retry(final ExecutionInfo executionInfo) {
        return executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

    private ExecutionInfo complete(final ExecutionInfo executionInfo) {
        return executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }
}
