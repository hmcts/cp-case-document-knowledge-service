package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static java.util.Objects.isNull;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_RAG_TRANSACTION_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.EMPTY_STRING;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.buildAnswerParams;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.buildReservationParams;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuidOrNull;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;

import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_STATUS_OF_ANSWER_GENERATION)
public class CheckStatusOfAnswerGenerationTask implements ExecutableTask {

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentInformationSummarisedAsynchronouslyApi documentInformationSummarisedAsynchronouslyApi;
    private final ObjectMapper objectMapper;

    static final String SQL_CREATE_OR_GET_VERSION =
            "SELECT get_or_create_answer_version(:case_id,:query_id,:doc_id)";
    static final String SQL_UPSERT_ANSWER =
            "INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) " +
                    "VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) " +
                    "ON CONFLICT (case_id, query_id, version) DO UPDATE SET " +
                    "  answer = EXCLUDED.answer, " +
                    "  llm_input = EXCLUDED.llm_input, " +
                    "  doc_id = EXCLUDED.doc_id, " +
                    "  created_at = EXCLUDED.created_at";

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final UUID transactionId = parseUuidOrNull(jobData.getString(CTX_RAG_TRANSACTION_ID, null));

        try {
            final ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> userQueryAnswerResponse = documentInformationSummarisedAsynchronouslyApi.answerUserQueryStatus(transactionId.toString());

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

            if (ANSWER_GENERATED == answerResponseBody.getStatus()) {
                final Integer version = getVersionNumber(caseId, queryId, documentId);
                final String llmInputJson = getLlmJson(answerResponseBody.getChunkedEntries(), caseId, documentId, queryId);

                final List<MapSqlParameterSource> params = new ArrayList<>();
                params.add(buildAnswerParams(caseId, queryId, version, answerResponseBody.getLlmResponse(), llmInputJson, documentId));
                jdbc.batchUpdate(SQL_UPSERT_ANSWER, params.toArray(new MapSqlParameterSource[0]));
            }

            if (ANSWER_GENERATION_FAILED == answerResponseBody.getStatus()) {
                log.info("Answer Generation Failed for caseId={}, docId={}, queryId={}, transactionId={}, task completed.",
                        caseId, documentId, queryId, transactionId);
            }

            return ExecutionInfo.executionInfo()
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
        return Optional.of(List.of(5L, 10L, 30L, 60L, 120L));
    }

    private ExecutionInfo retry(final ExecutionInfo executionInfo) {
        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

    private String getLlmJson(final List<Object> chunkedEntries, final UUID caseId, final UUID docId, final UUID queryId) {

        final Map<String, Object> chunkSampleMap = new LinkedHashMap<>();
        try {
            final List<Object> chunks = Optional.ofNullable(chunkedEntries).orElseGet(Collections::emptyList);
            chunkSampleMap.put("provenanceChunksSample", chunks);
            return objectMapper.writeValueAsString(chunkSampleMap);
        } catch (final Exception e) {
            log.warn("Failed to build llm_input JSON for caseId={}, docId={}, queryId={}: {}",
                    caseId, docId, queryId, e.getMessage(), e);
        }

        return EMPTY_STRING;
    }

    private Integer getVersionNumber(final UUID caseId, final UUID queryId, final UUID docId) {
        final MapSqlParameterSource paramsForReservation = buildReservationParams(caseId, queryId, docId);
        return jdbc.queryForObject(SQL_CREATE_OR_GET_VERSION, paramsForReservation, Integer.class);
    }

}
