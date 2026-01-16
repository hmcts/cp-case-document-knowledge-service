package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.util.Objects.isNull;
import static java.util.Optional.ofNullable;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GENERATE_ANSWER_FOR_QUERY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuidOrNull;

import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
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

    public static final String CTX_SINGLE_QUERY_ID = "CTX_SINGLE_QUERY_ID";
    public static final String CTX_RAG_TRANSACTION_ID = "ragTransactionId";

    private final QueryDefinitionLatestRepository queryDefinitionLatestRepository;
    private final DocumentInformationSummarisedAsynchronouslyApi documentInformationSummarisedAsynchronouslyApi;
    private final ExecutionService executionService;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final UUID caseId = parseUuidOrNull(jobData.getString(CTX_CASE_ID_KEY, null));
        final UUID docId = parseUuidOrNull(jobData.getString(CTX_DOC_ID_KEY, null));
        final UUID queryId = parseUuidOrNull(jobData.getString(CTX_SINGLE_QUERY_ID, null));

        if (isNull(caseId) || isNull(docId) || isNull(queryId)) {
            log.warn("GenerateAnswerForQueryTask: missing identifiers caseId={}, docId={}, queryId={}", caseId, docId, queryId);
            return completed(executionInfo);
        }

        final QueryDefinitionLatest qdl = queryDefinitionLatestRepository.findByQueryId(queryId).orElse(null);

        if (isNull(qdl)) {
            log.warn("No QueryDefinitionLatest found for queryId={}", queryId);
            return completed(executionInfo);
        }

        final AnswerUserQueryRequest request = new AnswerUserQueryRequest()
                .userQuery(ofNullable(qdl.getUserQuery()).orElse(""))
                .queryPrompt(ofNullable(qdl.getQueryPrompt()).orElse(""))
                .metadataFilter(List.of(new MetadataFilter()
                        .key("document_id")
                        .value(docId.toString()))
                );

        try {
            final ResponseEntity<@NotNull UserQueryAnswerRequestAccepted> userQueryResponse = documentInformationSummarisedAsynchronouslyApi.answerUserQueryAsync(request);

            final String transactionId = ofNullable(userQueryResponse.getBody())
                    .map(UserQueryAnswerRequestAccepted::getTransactionId)
                    .orElseThrow(() -> new IllegalStateException("Async RAG request did not return transactionId"));

            log.info("Async RAG started for caseId={}, docId={}, queryId={}, transactionId={}", caseId, docId, queryId, transactionId);

            final JsonObjectBuilder nextJobData = createObjectBuilder(jobData).add(CTX_RAG_TRANSACTION_ID, transactionId);

            final ExecutionInfo nextTask = ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withAssignedTaskName(CHECK_STATUS_OF_ANSWER_GENERATION)
                    .withJobData(nextJobData.build())
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();

            executionService.executeWith(nextTask);

            return completed(executionInfo);

        } catch (final Exception ex) {
            log.error("Failed to start async RAG for caseId={}, docId={}, queryId={}", caseId, docId, queryId, ex);

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }
    }

    private ExecutionInfo completed(final ExecutionInfo executionInfo) {
        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }
}