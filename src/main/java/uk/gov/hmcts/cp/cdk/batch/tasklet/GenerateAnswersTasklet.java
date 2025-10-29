package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
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
import java.util.stream.Collectors;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID;

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


    private static MapSqlParameterSource params(UUID caseId, UUID queryId, UUID docId) {
        return new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("doc_id", docId);
    }

    private static List<MetadataFilter> buildMetadataFilters(UUID caseId, UUID docId, UUID queryId) {
        return Collections.singletonList(
                new MetadataFilter().key("document_id").value(docId.toString())
        );
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final String caseIdStr = stepCtx.getString("caseId", null);
        final String docIdStr = stepCtx.getString(CTX_DOC_ID, null);
        if (caseIdStr == null || docIdStr == null) {
            log.debug("No caseId/docId in step context – nothing to do.");
            return RepeatStatus.FINISHED;
        }

        final UUID caseId = UUID.fromString(caseIdStr);
        final UUID docId = UUID.fromString(docIdStr);

        final List<Query> queries = queryResolver.resolve();
        if (queries.isEmpty()) {
            log.debug("No queries resolved – nothing to do.");
            return RepeatStatus.FINISHED;
        }

        final Map<UUID, QueryDefinitionLatest> defCache = new HashMap<>();

        final TransactionTemplate txRequired = new TransactionTemplate(txManager);
        txRequired.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        final List<MapSqlParameterSource> answerParams = new ArrayList<>(queries.size());
        final List<MapSqlParameterSource> doneParams = new ArrayList<>(queries.size());
        final List<MapSqlParameterSource> failedParams = new ArrayList<>();

        for (final Query query : queries) {
            final UUID queryId = query.getQueryId();

            final Integer version = txRequired.execute(status -> {
                final MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("case_id", caseId)
                        .addValue("query_id", queryId)
                        .addValue("doc_id", docId);

                final List<Integer> found = jdbc.query(SQL_FIND_VERSION, params, new SingleColumnRowMapper<>(Integer.class));
                Integer v = found.isEmpty() ? null : found.get(0);

                if (v == null) {
                    v = jdbc.queryForObject(SQL_CREATE_OR_GET_VERSION, params, Integer.class);
                }

                jdbc.update(SQL_MARK_IN_PROGRESS, params);
                return v;
            });

            final QueryDefinitionLatest qdl = defCache.computeIfAbsent(
                    queryId, k -> qdlRepo.findByQueryId(k).orElse(null)
            );

            final String userQuery = qdl != null ? qdl.getUserQuery() : "";
            final String queryPrompt = qdl != null ? qdl.getQueryPrompt() : "";

            final List<MetadataFilter> filters = buildMetadataFilters(caseId, docId, queryId);

            final UserQueryAnswerReturnedSuccessfully resp;
            try {
                final AnswerUserQueryRequest answerUserQueryRequest = new AnswerUserQueryRequest()
                        .userQuery(userQuery)
                        .queryPrompt(queryPrompt)
                        .metadataFilters(filters);
                var responseEntity =documentInformationSummarisedApi.answerUserQuery(answerUserQueryRequest);
                resp = (responseEntity != null) ? responseEntity.getBody() : null;
            } catch (Exception e) {
                log.warn("RAG call failed for caseId={}, docId={}, queryId={}: {}", caseId, docId, queryId, e.getMessage(), e);
                failedParams.add(params(caseId, queryId, docId));
                continue;
            }

            final String llmResponse = resp != null ? resp.getLlmResponse() : null;
            if (llmResponse == null || llmResponse.isBlank()) {
                log.warn("Empty llmResponse for caseId={}, docId={}, queryId={} – marking FAILED", caseId, docId, queryId);
                failedParams.add(params(caseId, queryId, docId));
                continue;
            }

            final String llmInputJson;
            try {
                llmInputJson = buildLlmInputJson(userQuery, queryPrompt, filters, resp, caseId, docId, queryId, version);
            } catch (Exception e) {
                log.warn("Failed to build llm_input JSON for caseId={}, docId={}, queryId={}: {}",
                        caseId, docId, queryId, e.getMessage(), e);
                failedParams.add(params(caseId, queryId, docId));
                continue;
            }

            answerParams.add(new MapSqlParameterSource()
                    .addValue("case_id", caseId)
                    .addValue("query_id", queryId)
                    .addValue("version", version)
                    .addValue("answer", llmResponse)
                    .addValue("llm_input", llmInputJson)
                    .addValue("doc_id", docId));

            doneParams.add(params(caseId, queryId, docId));
        }

        txRequired.execute(status -> {
            if (!answerParams.isEmpty()) {
                jdbc.batchUpdate(SQL_UPSERT_ANSWER, answerParams.toArray(new MapSqlParameterSource[0]));
            }
            if (!doneParams.isEmpty()) {
                jdbc.batchUpdate(SQL_MARK_DONE, doneParams.toArray(new MapSqlParameterSource[0]));
            }
            if (!failedParams.isEmpty()) {
                jdbc.batchUpdate(SQL_MARK_FAILED, failedParams.toArray(new MapSqlParameterSource[0]));
            }
            return null;
        });

        log.info("GenerateAnswersTasklet finished: {} done, {} failed", doneParams.size(), failedParams.size());
        return RepeatStatus.FINISHED;
    }

    @SuppressWarnings("unchecked")
    private String buildLlmInputJson(String userQuery,
                                     String queryPrompt,
                                     List<MetadataFilter> filters,
                                     UserQueryAnswerReturnedSuccessfully resp,
                                     UUID caseId,
                                     UUID docId,
                                     UUID queryId,
                                     Integer version) throws Exception {

        List<Object> chunks = Optional.ofNullable(resp.getChunkedEntries()).orElseGet(List::of);
        List<Object> compactChunks = chunks.stream().limit(5).collect(Collectors.toList());

        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("case_id", caseId.toString());
        payload.put("doc_id", docId.toString());
        payload.put("query_id", queryId.toString());
        payload.put("version", version);
        payload.put("userQuery", userQuery);
        payload.put("queryPrompt", queryPrompt);
        payload.put("metadataFilters", filters.stream()
                .map(f -> Map.of("key", f.getKey(), "value", f.getValue()))
                .collect(Collectors.toList()));
        payload.put("ragResponsePrompt", resp.getQueryPrompt()); // echo from service if provided
        payload.put("provenanceChunksSample", compactChunks);

        return objectMapper.writeValueAsString(payload);
    }
}
