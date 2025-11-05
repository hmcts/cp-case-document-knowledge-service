package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.cdk.batch.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.util.*;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_UPLOAD_VERIFIED_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateAnswersTasklet implements Tasklet {

    private final QueryResolver queryResolver;
    private final QueryDefinitionLatestRepository qdlRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final ObjectMapper objectMapper;
    private final DocumentInformationSummarisedApi documentInformationSummarisedApi;

    private static final String SQL_FIND_VERSION =
            "SELECT version FROM answer_reservations " +
                    "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id";

    private static final String SQL_CREATE_OR_GET_VERSION =
            "SELECT get_or_create_answer_version(:case_id,:query_id,:doc_id)";

    private static final String SQL_MARK_IN_PROGRESS =
            "UPDATE answer_reservations " +
                    "SET status='IN_PROGRESS', updated_at=NOW() " +
                    "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id " +
                    "AND status IN ('NEW','FAILED')";

    private static final String SQL_UPSERT_ANSWER =
            "INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) " +
                    "VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) " +
                    "ON CONFLICT (case_id, query_id, version) DO UPDATE SET " +
                    "answer = EXCLUDED.answer, llm_input = EXCLUDED.llm_input, doc_id = EXCLUDED.doc_id, " +
                    "created_at = EXCLUDED.created_at";

    private static final String SQL_MARK_DONE =
            "UPDATE answer_reservations SET status='DONE', updated_at=NOW() " +
                    "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id";

    private static final String SQL_MARK_FAILED =
            "UPDATE answer_reservations SET status='FAILED', updated_at=NOW() " +
                    "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id";

    private static final SingleColumnRowMapper<Integer> INT_ROW_MAPPER =
            new SingleColumnRowMapper<>(Integer.class);

    private static MapSqlParameterSource reservationParams(
            final UUID caseId, final UUID queryId, final UUID docId
    ) {
        return new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("doc_id", docId);
    }

    private static MapSqlParameterSource answerParamsRow(
            final UUID caseId,
            final UUID queryId,
            final Integer version,
            final String answer,
            final String llmInput,
            final UUID docId
    ) {
        return new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("version", version)
                .addValue("answer", answer)
                .addValue("llm_input", llmInput)
                .addValue("doc_id", docId);
    }

    private static List<MetadataFilter> buildMetadataFilters(final UUID docId) {
        return Collections.singletonList(
                new MetadataFilter().key("document_id").value(docId.toString())
        );
    }

    @Override
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.OnlyOneReturn"})
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final JobExecution jobExecution = contribution.getStepExecution().getJobExecution();
        final ExecutionContext jobCtx = jobExecution != null ? jobExecution.getExecutionContext() : new ExecutionContext();

        final String docIdStr = stepCtx.getString(CTX_DOC_ID_KEY, null);
        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docIdStr;
        final Boolean jobVerified  = (Boolean) jobCtx.get(verifiedKey);
        if (!Boolean.TRUE.equals(jobVerified)) {
            log.info("GenerateAnswersTasklet: ingestion not verified as SUCCESS; skipping.");
            return RepeatStatus.FINISHED;
        }
        final String caseIdStr = stepCtx.getString(CTX_CASE_ID_KEY, null);


        if (caseIdStr != null && docIdStr != null) {
            final UUID caseId = UUID.fromString(caseIdStr);
            final UUID docId = UUID.fromString(docIdStr);
            final List<Query> queries = queryResolver.resolve();
            if (queries.isEmpty()) {
                log.debug("No queries resolved – nothing to do.");
            } else {
                final Map<UUID, QueryDefinitionLatest> defCache = new HashMap<>();
                final TransactionTemplate txRequired = new TransactionTemplate(txManager);
                txRequired.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

                final List<MapSqlParameterSource> answersBatch = new ArrayList<>(queries.size());
                final List<MapSqlParameterSource> doneBatch = new ArrayList<>(queries.size());
                final List<MapSqlParameterSource> failedBatch = new ArrayList<>();

                for (final Query query : queries) {
                    final UUID queryId = query.getQueryId();

                    final Integer version = txRequired.execute(status -> {
                        final MapSqlParameterSource params = reservationParams(caseId, queryId, docId);

                        final List<Integer> found = jdbc.query(SQL_FIND_VERSION, params, INT_ROW_MAPPER);
                        Integer foundVersion = found.isEmpty() ? null : found.get(0);

                        if (foundVersion == null) {
                            foundVersion = jdbc.queryForObject(SQL_CREATE_OR_GET_VERSION, params, Integer.class);
                        }

                        jdbc.update(SQL_MARK_IN_PROGRESS, params);
                        return foundVersion;
                    });

                    final QueryDefinitionLatest qdl = defCache.computeIfAbsent(
                            queryId, k -> qdlRepo.findByQueryId(k).orElse(null)
                    );

                    final String userQuery = qdl != null ? Optional.ofNullable(qdl.getUserQuery()).orElse("") : "";
                    final String queryPrompt = qdl != null ? Optional.ofNullable(qdl.getQueryPrompt()).orElse("") : "";
                    final List<MetadataFilter> filters = buildMetadataFilters(docId);

                    final AnswerUserQueryRequest request =
                            buildAnswerUserQueryRequest(userQuery, queryPrompt, filters);

                    final Optional<UserQueryAnswerReturnedSuccessfully> respOpt;
                    try {
                        final ResponseEntity<UserQueryAnswerReturnedSuccessfully> responseEntity =
                                documentInformationSummarisedApi.answerUserQuery(request);
                        respOpt = Optional.ofNullable(responseEntity).map(ResponseEntity::getBody);
                    } catch (final Exception e) {
                        log.warn("RAG call failed for caseId={}, docId={}, queryId={}: {}",
                                caseId, docId, queryId, e.getMessage(), e);
                        failedBatch.add(reservationParams(caseId, queryId, docId));
                        continue;
                    }

                    if (!respOpt.isPresent()) {
                        log.warn("Empty response entity/body for caseId={}, docId={}, queryId={} – marking FAILED",
                                caseId, docId, queryId);
                        failedBatch.add(reservationParams(caseId, queryId, docId));
                        continue;
                    }

                    final UserQueryAnswerReturnedSuccessfully resp = respOpt.get();
                    final String llmResponse = resp.getLlmResponse();
                    if (llmResponse == null || llmResponse.isBlank()) {
                        log.warn("Empty llmResponse for caseId={}, docId={}, queryId={} – marking FAILED",
                                caseId, docId, queryId);
                        failedBatch.add(reservationParams(caseId, queryId, docId));
                        continue;
                    }

                    final String llmInputJson;
                    try {
                        llmInputJson = buildLlmInputJson(resp);
                    } catch (final Exception e) {
                        log.warn("Failed to build llm_input JSON for caseId={}, docId={}, queryId={}: {}",
                                caseId, docId, queryId, e.getMessage(), e);
                        failedBatch.add(reservationParams(caseId, queryId, docId));
                        continue;
                    }

                    answersBatch.add(answerParamsRow(caseId, queryId, version, llmResponse, llmInputJson, docId));
                    doneBatch.add(reservationParams(caseId, queryId, docId));
                }

                txRequired.execute(status -> {
                    if (!answersBatch.isEmpty()) {
                        jdbc.batchUpdate(SQL_UPSERT_ANSWER, answersBatch.toArray(new MapSqlParameterSource[0]));
                    }
                    if (!doneBatch.isEmpty()) {
                        jdbc.batchUpdate(SQL_MARK_DONE, doneBatch.toArray(new MapSqlParameterSource[0]));
                    }
                    if (!failedBatch.isEmpty()) {
                        jdbc.batchUpdate(SQL_MARK_FAILED, failedBatch.toArray(new MapSqlParameterSource[0]));
                    }
                    return null;
                });

                log.info("GenerateAnswersTasklet finished: {} done, {} failed",
                        doneBatch.size(), failedBatch.size());
            }
        } else {
            log.debug("No caseId/docId in step context – nothing to do.");
        }

        return RepeatStatus.FINISHED;
    }

    private static AnswerUserQueryRequest buildAnswerUserQueryRequest(
            final String userQuery,
            final String queryPrompt,
            final List<MetadataFilter> filters
    ) {
        return new AnswerUserQueryRequest()
                .userQuery(userQuery)
                .queryPrompt(queryPrompt)
                .metadataFilters(filters);
    }

    private String buildLlmInputJson(final UserQueryAnswerReturnedSuccessfully resp) throws Exception {
        final List<Object> chunks = Optional.ofNullable(resp.getChunkedEntries()).orElseGet(Collections::emptyList);
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provenanceChunksSample", chunks);
        return objectMapper.writeValueAsString(payload);
    }
}
