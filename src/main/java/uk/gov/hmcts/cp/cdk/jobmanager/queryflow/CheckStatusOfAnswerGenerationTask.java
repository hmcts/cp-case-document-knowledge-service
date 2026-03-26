package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static java.util.Objects.isNull;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_QUERY_LEVEL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_RAG_TRANSACTION_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.EMPTY_STRING;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseQueryLevel;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuidOrNull;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.QueryLevel;
import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.services.AnswerGenerationService;
import uk.gov.hmcts.cp.cdk.services.CaseLevelAllDocumentsAnswerService;
import uk.gov.hmcts.cp.cdk.services.CaseLevelLatestDocumentAnswerService;
import uk.gov.hmcts.cp.cdk.services.DefendantAnswerService;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.model.DocumentChunk;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_STATUS_OF_ANSWER_GENERATION)
public class CheckStatusOfAnswerGenerationTask implements ExecutableTask {

    private static final String PROVENANCE_CHUNKS_SAMPLE = "provenanceChunksSample";

    private final DocumentInformationSummarisedAsynchronouslyApi documentInformationSummarisedAsynchronouslyApi;
    private final ObjectMapper objectMapper;
    private final JobManagerRetryProperties retryProperties;
    private final AnswerGenerationService answerGenerationService;
    private final CaseLevelAllDocumentsAnswerService caseLevelAllDocumentsAnswerService;
    private final CaseLevelLatestDocumentAnswerService caseLevelLatestDocumentAnswerService;
    private final DefendantAnswerService defendantAnswerService;
    private final IngestionProperties ingestionProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final UUID transactionId = parseUuidOrNull(jobData.getString(CTX_RAG_TRANSACTION_ID, null));
        final boolean isUseMultiDefendant = ingestionProperties.getFeature().isUseMultiDefendant();

        try {
            final ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> userQueryAnswerResponse = documentInformationSummarisedAsynchronouslyApi.answerUserQueryStatus(transactionId.toString(), true);

            if (isNull(userQueryAnswerResponse)
                    || !userQueryAnswerResponse.getStatusCode().is2xxSuccessful()
                    || isNull(userQueryAnswerResponse.getBody())
                    || ANSWER_GENERATION_PENDING == userQueryAnswerResponse.getBody().getStatus()) {

                log.info("Answer Generation in progress for the transactionId={} → retrying", transactionId);
                return retry(executionInfo);
            }

            //persist answer response
            final UserQueryAnswerReturnedSuccessfullyAsynchronously answerResponseBody = userQueryAnswerResponse.getBody();
            final UUID caseId = parseUuidOrNull(jobData.getString(CTX_CASE_ID_KEY, null));
            final UUID documentId = parseUuidOrNull(jobData.getString(CTX_DOC_ID_KEY, null));
            final UUID queryId = parseUuidOrNull(jobData.getString(CTX_SINGLE_QUERY_ID, null));
            final UUID defendantId = parseUuidOrNull(jobData.getString(CTX_DEFENDANT_ID_KEY, null));
            final String levelStr = jobData.getString(CTX_QUERY_LEVEL, null);
            final QueryLevel level = parseQueryLevel(levelStr);

            if (ANSWER_GENERATED == answerResponseBody.getStatus()) {
                final String llmInputJson = getLlmJson(answerResponseBody.getDocumentChunks(), caseId, documentId, queryId);
                if (!isUseMultiDefendant) {
                    answerGenerationService.upsertAnswer(caseId, queryId, answerResponseBody.getLlmResponse(), llmInputJson, documentId);
                } else {
                    switch (level) {
                        case QueryLevel.CASE:
                            caseLevelLatestDocumentAnswerService.upsert(
                                    caseId,
                                    queryId,
                                    answerResponseBody.getLlmResponse(),
                                    llmInputJson,
                                    documentId
                            );
                            break;

                        case QueryLevel.CASE_ALL_DOCUMENTS:
                            caseLevelAllDocumentsAnswerService.upsert(
                                    caseId,
                                    queryId,
                                    answerResponseBody.getLlmResponse(),
                                    llmInputJson
                            );
                            break;

                        case QueryLevel.DEFENDANT:
                            defendantAnswerService.upsert(
                                    caseId,
                                    queryId,
                                    defendantId,
                                    answerResponseBody.getLlmResponse(),
                                    llmInputJson,
                                    documentId
                            );
                            break;
                        case null, default:
                            answerGenerationService.upsertAnswer(
                                    caseId,
                                    queryId,
                                    answerResponseBody.getLlmResponse(),
                                    llmInputJson,
                                    documentId
                            );
                            break;
                    }

                }

                log.info("Answer Generation updated in the DB for caseId={}, docId={}, queryId={}, transactionId={}, task completed.",
                        caseId, documentId, queryId, transactionId);
            }

            if (ANSWER_GENERATION_FAILED == answerResponseBody.getStatus()) {
                log.info("Answer Generation Failed for caseId={}, docId={}, queryId={}, transactionId={}, task completed.",
                        caseId, documentId, queryId, transactionId);
            }

            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (final Exception ex) {
            log.error("Failed to check answer generation status RAG for transactionId={}", transactionId, ex);
            return retry(executionInfo);
        }
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        final var retry = retryProperties.getQuestionsRetry();
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

    private String getLlmJson(final List<DocumentChunk> chunkedEntries, final UUID caseId, final UUID docId, final UUID queryId) {

        final Map<String, Object> chunkSampleMap = new LinkedHashMap<>();
        try {
            final List<DocumentChunk> chunks = Optional.ofNullable(chunkedEntries).orElseGet(Collections::emptyList);
            chunkSampleMap.put(PROVENANCE_CHUNKS_SAMPLE, chunks);
            return objectMapper.writeValueAsString(chunkSampleMap);
        } catch (final Exception e) {
            log.warn("Failed to build llm_input JSON for caseId={}, docId={}, queryId={}: {}",
                    caseId, docId, queryId, e.getMessage(), e);
        }

        return EMPTY_STRING;
    }




}
