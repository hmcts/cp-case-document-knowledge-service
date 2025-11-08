package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.cdk.batch.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.util.*;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.*;

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
    private final RetryTemplate retryTemplate;
    private final DocumentIdResolver documentIdResolver;

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

    private static MapSqlParameterSource reservationParams(final UUID caseId, final UUID queryId, final UUID docId) {
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
    @SuppressWarnings({ "PMD.CyclomaticComplexity", "PMD.OnlyOneReturn" })
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final JobExecution jobExecution = contribution.getStepExecution().getJobExecution();
        final ExecutionContext jobCtx = jobExecution != null ? jobExecution.getExecutionContext() : new ExecutionContext();

        // Read from context
        final String caseIdStr = stepCtx.getString(CTX_CASE_ID_KEY, null);
        final String docIdStrCtx = stepCtx.getString(CTX_DOC_ID_KEY, null);
        final String materialIdStr = stepCtx.getString(CTX_MATERIAL_ID_KEY, null);

        if (caseIdStr == null) {
            log.debug("GenerateAnswersTasklet: No caseId in step context – nothing to do.");
            return RepeatStatus.FINISHED;
        }

        final UUID caseId;
        try {
            caseId = UUID.fromString(caseIdStr);
        } catch (IllegalArgumentException ex) {
            log.warn("GenerateAnswersTasklet: Invalid caseId='{}' — skipping.", caseIdStr);
            return RepeatStatus.FINISHED;
        }

        UUID docId = null;
        if (docIdStrCtx != null) {
            try {
                docId = UUID.fromString(docIdStrCtx);
            } catch (IllegalArgumentException ex) {
                log.debug("GenerateAnswersTasklet: ignoring invalid docId in context: '{}'", docIdStrCtx);
            }
        }

        // Prefer authoritative docId if (caseId, materialId) exists
        if (materialIdStr != null) {
            try {
                final UUID materialId = UUID.fromString(materialIdStr);
                final Optional<UUID> existing = documentIdResolver.resolveExistingDocId(caseId, materialId);
                if (existing.isPresent() && !existing.get().equals(docId)) {
                    log.info("GenerateAnswersTasklet: overriding docId from context {} -> {} based on DB for caseId={}, materialId={}",
                        docId, existing.get(), caseId, materialId);
                    docId = existing.get();
                    stepCtx.putString(CTX_DOC_ID_KEY, docId.toString()); // keep downstream in sync
                }
            } catch (IllegalArgumentException ex) {
                log.debug("GenerateAnswersTasklet: invalid materialId='{}' — continuing with context docId.", materialIdStr);
            }
        }

        if (docId == null) {
            log.debug("GenerateAnswersTasklet: No docId available after DB resolution – nothing to do.");
            return RepeatStatus.FINISHED;
        }

        // Make final copies for all lambdas below
        final UUID resolvedCaseId = caseId;
        final UUID resolvedDocId  = docId;

        // Check ingestion verified for resolved docId
        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + resolvedDocId;
        final Boolean jobVerified  = (Boolean) jobCtx.get(verifiedKey);
        if (!Boolean.TRUE.equals(jobVerified)) {
            log.info("GenerateAnswersTasklet: ingestion not verified as SUCCESS for docId={}; skipping.", resolvedDocId);
            return RepeatStatus.FINISHED;
        }

        List<Query> queries = queryResolver.resolve();

        final JobParameters params = jobExecution != null ? jobExecution.getJobParameters() : null;
        final String singleQueryFromStep = stepCtx.getString("CTX_SINGLE_QUERY_ID", null);
        final String singleQueryFromParams = params != null ? params.getString("CTX_SINGLE_QUERY_ID", null) : null;
        final String singleQueryIdStr = singleQueryFromStep != null ? singleQueryFromStep : singleQueryFromParams;

        if (singleQueryIdStr != null && !singleQueryIdStr.isBlank()) {
            try {
                final UUID only = UUID.fromString(singleQueryIdStr);
                queries = new ArrayList<>(queries);
                queries.removeIf(q -> q == null || q.getQueryId() == null || !only.equals(q.getQueryId()));
                log.info("Single-query mode: running only queryId={}", singleQueryIdStr);
            } catch (IllegalArgumentException ex) {
                log.warn("Invalid CTX_SINGLE_QUERY_ID='{}' — ignoring single-query filter.", singleQueryIdStr);
            }
        }

        if (queries == null || queries.isEmpty()) {
            log.debug("No queries resolved – nothing to do.");
            return RepeatStatus.FINISHED;
        }

        final Map<UUID, QueryDefinitionLatest> defCache = new HashMap<>();
        final TransactionTemplate txRequired = new TransactionTemplate(txManager);
        txRequired.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        final List<MapSqlParameterSource> answersBatch = new ArrayList<>(queries.size());
        final List<MapSqlParameterSource> doneBatch = new ArrayList<>(queries.size());
        final List<MapSqlParameterSource> failedBatch = new ArrayList<>();

        // Pre-create reusable objects to satisfy PMD "avoid instantiating objects in loops"
        final List<MetadataFilter> metaFilters = buildMetadataFilters(resolvedDocId);
        final AnswerUserQueryRequest reusableRequest = new AnswerUserQueryRequest().metadataFilter(metaFilters);
        final Map<String, Object> reusablePayload = new LinkedHashMap<>();

        for (final Query query : queries) {
            if (query == null || query.getQueryId() == null) {
                continue;
            }
            final UUID queryId = query.getQueryId();

            final Integer version = txRequired.execute(status -> {
                final MapSqlParameterSource params2 = reservationParams(resolvedCaseId, queryId, resolvedDocId);

                final List<Integer> found = jdbc.query(SQL_FIND_VERSION, params2, INT_ROW_MAPPER);
                Integer foundVersion = found.isEmpty() ? null : found.get(0);

                if (foundVersion == null) {
                    foundVersion = jdbc.queryForObject(SQL_CREATE_OR_GET_VERSION, params2, Integer.class);
                }

                jdbc.update(SQL_MARK_IN_PROGRESS, params2);
                return foundVersion;
            });

            final QueryDefinitionLatest qdl = defCache.computeIfAbsent(
                queryId, k -> qdlRepo.findByQueryId(k).orElse(null)
            );

            final String userQuery = qdl != null ? Optional.ofNullable(qdl.getUserQuery()).orElse("") : "";
            final String queryPrompt = qdl != null ? Optional.ofNullable(qdl.getQueryPrompt()).orElse("") : "";

            // Reuse metadata filter + request instance
            reusableRequest.userQuery(userQuery).queryPrompt(queryPrompt);

            // ==== APIM call with retry ====
            final long started = System.currentTimeMillis();
            final UserQueryAnswerReturnedSuccessfully resp =
                retryTemplate.execute((RetryContext context) -> {
                    if (context.getRetryCount() > 0) {
                        log.warn("Retrying APIM call (attempt #{}) caseId={}, docId={}, queryId={}",
                                context.getRetryCount() + 1, resolvedCaseId, resolvedDocId, queryId);
                    }

                    final ResponseEntity<UserQueryAnswerReturnedSuccessfully> responseEntity =
                        documentInformationSummarisedApi.answerUserQuery(reusableRequest);

                    if (responseEntity == null || responseEntity.getBody() == null) {
                        throw new IllegalStateException("Empty response body from APIM");
                    }

                    final UserQueryAnswerReturnedSuccessfully body = responseEntity.getBody();
                    final String llmResponse = body.getLlmResponse();
                    if (llmResponse == null || llmResponse.isBlank()) {
                        throw new IllegalStateException("Empty llmResponse from APIM");
                    }
                    return body;
                }, (RetryContext context) -> {
                    log.warn("APIM call permanently failed after {} attempts for caseId={}, docId={}, queryId={}",
                            context.getRetryCount(), resolvedCaseId, resolvedDocId, queryId, context.getLastThrowable());
                    failedBatch.add(reservationParams(resolvedCaseId, queryId, resolvedDocId));
                    return null;
                });
            log.info("RAG call duration for queryId={}: {} ms", queryId, System.currentTimeMillis() - started);

            if (resp == null) {
                continue;
            }

            final String llmInputJson;
            try {
                // Reuse payload map instance
                reusablePayload.clear();
                final List<Object> chunks = Optional.ofNullable(resp.getChunkedEntries()).orElseGet(Collections::emptyList);
                reusablePayload.put("provenanceChunksSample", chunks);
                llmInputJson = objectMapper.writeValueAsString(reusablePayload);
            } catch (final Exception e) {
                log.warn("Failed to build llm_input JSON for caseId={}, docId={}, queryId={}: {}",
                        resolvedCaseId, resolvedDocId, queryId, e.getMessage(), e);
                failedBatch.add(reservationParams(resolvedCaseId, queryId, resolvedDocId));
                continue;
            }

            answersBatch.add(answerParamsRow(resolvedCaseId, queryId, version, resp.getLlmResponse(), llmInputJson, resolvedDocId));
            doneBatch.add(reservationParams(resolvedCaseId, queryId, resolvedDocId));
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

        log.info("GenerateAnswersTasklet finished: {} done, {} failed", doneBatch.size(), failedBatch.size());
        return RepeatStatus.FINISHED;
    }
}
