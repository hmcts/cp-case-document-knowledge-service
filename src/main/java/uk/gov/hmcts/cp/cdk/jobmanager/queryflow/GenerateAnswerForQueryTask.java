package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.util.Objects.isNull;
import static java.util.Optional.ofNullable;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GENERATE_ANSWER_FOR_QUERY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.BlobMetadataKeys.META_CASE_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.BlobMetadataKeys.META_DOCUMENT_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_QUERY_LEVEL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_RAG_TRANSACTION_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseQueryLevel;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuidOrNull;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
import uk.gov.hmcts.cp.cdk.domain.QueryLevel;
import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerRequestAccepted;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.UUID;

import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(GENERATE_ANSWER_FOR_QUERY)
public class GenerateAnswerForQueryTask implements ExecutableTask {

    private final QueryDefinitionLatestRepository queryDefinitionLatestRepository;
    private final DocumentInformationSummarisedAsynchronouslyApi documentInformationSummarisedAsynchronouslyApi;
    private final ExecutionService executionService;
    private final IngestionProperties ingestionProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final UUID caseId = parseUuidOrNull(jobData.getString(CTX_CASE_ID_KEY, null));
        final UUID docId = parseUuidOrNull(jobData.getString(CTX_DOC_ID_KEY, null));
        final UUID queryId = parseUuidOrNull(jobData.getString(CTX_SINGLE_QUERY_ID, null));
        final boolean isUseMultiDefendant = ingestionProperties.getFeature().isUseMultiDefendant();
        final String levelStr = jobData.getString(CTX_QUERY_LEVEL, null);


        if (isNull(caseId) || isNull(docId) || isNull(queryId)) {
            log.warn("GenerateAnswerForQueryTask: missing identifiers caseId={}, docId={}, queryId={}", caseId, docId, queryId);
            return completed(executionInfo);
        }
        final QueryLevel level = parseQueryLevel(levelStr);
        final MetadataFilter filter;
        if (isUseMultiDefendant && level == QueryLevel.CASE_ALL_DOCUMENTS) {
            filter = new MetadataFilter()
                    .key(META_CASE_ID)
                    .value(caseId.toString());
        } else {
            filter = new MetadataFilter()
                    .key(META_DOCUMENT_ID)
                    .value(docId.toString());
        }

        final QueryDefinitionLatest qdl = queryDefinitionLatestRepository.findByQueryId(queryId).orElse(null);

        if (isNull(qdl)) {
            log.warn("No QueryDefinitionLatest found for queryId={}", queryId);
            return completed(executionInfo);
        }

        final AnswerUserQueryRequest request = new AnswerUserQueryRequest()
                .userQuery(ofNullable(qdl.getUserQuery()).orElse(""))
                .queryPrompt(ofNullable(qdl.getQueryPrompt()).orElse(""))
                .metadataFilter(List.of(filter)
                );

        try {
            final ResponseEntity<@NotNull UserQueryAnswerRequestAccepted> userQueryResponse = documentInformationSummarisedAsynchronouslyApi.answerUserQueryAsync(request);

            final String transactionId = ofNullable(userQueryResponse.getBody())
                    .map(UserQueryAnswerRequestAccepted::getTransactionId)
                    .orElseThrow(() -> new IllegalStateException("Async RAG request did not return transactionId"));

            log.info("Async RAG started for caseId={}, docId={}, queryId={}, transactionId={}", caseId, docId, queryId, transactionId);

            final JsonObjectBuilder nextJobData = createObjectBuilder(jobData).add(CTX_RAG_TRANSACTION_ID, transactionId);

            final ExecutionInfo nextTask = executionInfo()
                    .from(executionInfo)
                    .withAssignedTaskName(CHECK_STATUS_OF_ANSWER_GENERATION)
                    .withJobData(nextJobData.build())
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();

            executionService.executeWith(nextTask);

            return completed(executionInfo);

        } catch (final Exception ex) {
            log.error("Failed to start async RAG for caseId={}, docId={}, queryId={}", caseId, docId, queryId, ex);

            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }
    }

    private ExecutionInfo completed(final ExecutionInfo executionInfo) {
        return executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }
}