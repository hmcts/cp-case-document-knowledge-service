package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_UPLOAD_VERIFIED_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.buildAnswerParams;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.buildReservationParams;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.parseUuidOrNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
import uk.gov.hmcts.cp.cdk.batch.support.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateAnswersTasklet implements Tasklet {

    private static final String CTX_SINGLE_QUERY_ID = "CTX_SINGLE_QUERY_ID";

    private static final String SQL_ELIGIBLE_QIDS =
            "SELECT query_id FROM answer_reservations " +
                    " WHERE case_id=:case_id AND doc_id=:doc_id AND status IN ('NEW','FAILED')";

    private static final String SQL_FIND_VERSION =
            "SELECT version FROM answer_reservations " +
                    " WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id";

    private static final String SQL_CREATE_OR_GET_VERSION =
            "SELECT get_or_create_answer_version(:case_id,:query_id,:doc_id)";

    private static final String SQL_MARK_IN_PROGRESS =
            "UPDATE answer_reservations " +
                    "   SET status='IN_PROGRESS', updated_at=NOW() " +
                    " WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id " +
                    "   AND status IN ('NEW','FAILED')";

    private static final String SQL_UPSERT_ANSWER =
            "INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) " +
                    "VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) " +
                    "ON CONFLICT (case_id, query_id, version) DO UPDATE SET " +
                    "  answer = EXCLUDED.answer, " +
                    "  llm_input = EXCLUDED.llm_input, " +
                    "  doc_id = EXCLUDED.doc_id, " +
                    "  created_at = EXCLUDED.created_at";

    private static final String SQL_MARK_DONE =
            "UPDATE answer_reservations SET status='DONE', updated_at=NOW() " +
                    " WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id";

    private static final String SQL_MARK_FAILED =
            "UPDATE answer_reservations SET status='FAILED', updated_at=NOW() " +
                    " WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id";

    private static final SingleColumnRowMapper<Integer> INT_ROW_MAPPER =
            new SingleColumnRowMapper<>(Integer.class);

    private final QueryResolver queryResolver;
    private final QueryDefinitionLatestRepository qdlRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final ObjectMapper objectMapper;
    private final DocumentInformationSummarisedApi documentInformationSummarisedApi;
    private final RetryTemplate retryTemplate;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        boolean proceed = true;

        final JobExecution jobExecution = contribution.getStepExecution().getJobExecution();
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final ExecutionContext jobCtx = jobExecution != null ? jobExecution.getExecutionContext() : new ExecutionContext();

        final String caseIdStr = stepCtx.getString(CTX_CASE_ID_KEY, null);
        final String docIdStr = stepCtx.getString(CTX_DOC_ID_KEY, null);
        final String materialIdStr = stepCtx.getString(CTX_MATERIAL_ID_KEY, null);
        if (caseIdStr == null || docIdStr == null) {
            log.debug("GenerateAnswersTasklet: Missing caseId/docId – nothing to do.");
            proceed = false;
        }

        final UUID caseId;
        final UUID docId;
        if (proceed) {
            UUID parsedCase = null;
            UUID parsedDoc = null;
            try {
                parsedCase = UUID.fromString(caseIdStr);
                parsedDoc = UUID.fromString(docIdStr);
            } catch (IllegalArgumentException ex) {
                log.warn("GenerateAnswersTasklet: Invalid caseId/docId — skipping. caseId='{}' docId='{}'", caseIdStr, docIdStr);
                proceed = false;
            }
            caseId = parsedCase;
            docId = parsedDoc;
        } else {
            caseId = null;
            docId = null;
        }

        final String verifiedKey = proceed ? CTX_UPLOAD_VERIFIED_KEY + ":" + docId : null;
        if (proceed) {
            final Boolean jobVerified = (Boolean) jobCtx.get(verifiedKey);
            if (!Boolean.TRUE.equals(jobVerified)) {
                log.info("GenerateAnswersTasklet: ingestion not verified as SUCCESS for docId={}; skipping.", docId);
                proceed = false;
            }
        }

        final List<Query> resolvedQueries;
        final Set<UUID> resolvedIds;
        if (proceed) {
            resolvedQueries = queryResolver.resolve();
            if (resolvedQueries == null || resolvedQueries.isEmpty()) {
                log.debug("GenerateAnswersTasklet: No queries resolved – nothing to do.");
                proceed = false;
            }
            resolvedIds = proceed
                    ? resolvedQueries.stream()
                    .map(Query::getQueryId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    : Collections.emptySet();
            if (proceed && resolvedIds.isEmpty()) {
                log.debug("GenerateAnswersTasklet: All resolved queries had null IDs – nothing to do.");
                proceed = false;
            }
        } else {
            resolvedIds = Set.of();
        }

        final UUID singleQueryId;
        if (proceed) {
            final JobParameters params = jobExecution != null ? jobExecution.getJobParameters() : null;
            final String singleQueryFromStep = stepCtx.getString(CTX_SINGLE_QUERY_ID, null);
            final String singleQueryFromParams = params != null ? params.getString(CTX_SINGLE_QUERY_ID, null) : null;
            final String singleQueryIdStr = singleQueryFromStep != null ? singleQueryFromStep : singleQueryFromParams;
            singleQueryId = parseUuidOrNull(singleQueryIdStr);
        } else {
            singleQueryId = null;
        }

        final Set<UUID> eligibleIds;
        if (proceed) {
            final MapSqlParameterSource base = new MapSqlParameterSource()
                    .addValue("case_id", caseId)
                    .addValue("doc_id", docId);

            final List<UUID> eligibleFromDb = jdbc.query(SQL_ELIGIBLE_QIDS, base,
                    (rs, rowNum) -> rs.getObject("query_id", UUID.class));
            eligibleIds = new LinkedHashSet<>(eligibleFromDb);
            eligibleIds.retainAll(resolvedIds);
            if (singleQueryId != null) {
                eligibleIds.retainAll(Collections.singleton(singleQueryId));
                log.info("Single-query mode in GenerateAnswersTasklet: candidate queryId={}", singleQueryId);
            }
            if (eligibleIds.isEmpty()) {
                log.info("GenerateAnswersTasklet: No eligible (NEW/FAILED) reservations to process (caseId={}, docId={}, materialId={}).",
                        caseId, docId, materialIdStr);
                proceed = false;
            }
        } else {
            eligibleIds = Set.of();
        }

        final TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        final Map<UUID, QueryDefinitionLatest> defCache = new HashMap<>();
        final List<MapSqlParameterSource> answersBatch = new ArrayList<>();
        final List<MapSqlParameterSource> doneBatch = new ArrayList<>();
        final List<MapSqlParameterSource> failedBatch = new ArrayList<>();

        if (proceed) {
            final List<MetadataFilter> metaFilters =
                    List.of(new MetadataFilter().key("document_id").value(docId.toString()));
            final AnswerUserQueryRequest reusableRequest = new AnswerUserQueryRequest().metadataFilter(metaFilters);
            final Map<String, Object> reusablePayload = new LinkedHashMap<>();

            for (final UUID queryId : eligibleIds) {
                final WorkPlan plan = transactionTemplate.execute(status -> {
                    final MapSqlParameterSource paramsForReservation = buildReservationParams(caseId, queryId, docId);

                    Integer versionNumber = jdbc.query(SQL_FIND_VERSION, paramsForReservation, INT_ROW_MAPPER)
                            .stream().findFirst().orElse(null);
                    if (versionNumber == null) {
                        versionNumber = jdbc.queryForObject(SQL_CREATE_OR_GET_VERSION, paramsForReservation, Integer.class);
                    }

                    final int updated = jdbc.update(SQL_MARK_IN_PROGRESS, paramsForReservation);
                    final boolean shouldDoWork = updated > 0;
                    return new WorkPlan(versionNumber, shouldDoWork);
                });

                if (plan == null || !plan.doWork) {
                    log.debug("GenerateAnswersTasklet: Skipping queryId={} (not eligible / already taken / done).", queryId);
                    continue;
                }

                final QueryDefinitionLatest qdl = defCache.computeIfAbsent(
                        queryId, k -> qdlRepo.findByQueryId(k).orElse(null)
                );
                final String userQuery = qdl != null ? Optional.ofNullable(qdl.getUserQuery()).orElse("") : "";
                final String queryPrompt = qdl != null ? Optional.ofNullable(qdl.getQueryPrompt()).orElse("") : "";

                reusableRequest.userQuery(userQuery).queryPrompt(queryPrompt);

                final long started = System.currentTimeMillis();
                final UserQueryAnswerReturnedSuccessfully resp =
                        retryTemplate.execute((RetryContext retryCtx) -> {
                            if (retryCtx.getRetryCount() > 0) {
                                log.warn("Retrying RAG call (attempt #{}) caseId={}, docId={}, queryId={}",
                                        retryCtx.getRetryCount() + 1, caseId, docId, queryId);
                            }

                            final ResponseEntity<UserQueryAnswerReturnedSuccessfully> responseEntity =
                                    documentInformationSummarisedApi.answerUserQuery(reusableRequest);

                            if (responseEntity == null || responseEntity.getBody() == null) {
                                throw new IllegalStateException("Empty response body from RAG");
                            }
                            final UserQueryAnswerReturnedSuccessfully body = responseEntity.getBody();
                            final String llmResponse = body.getLlmResponse();
                            if (llmResponse == null || llmResponse.isBlank()) {
                                throw new IllegalStateException("Empty llmResponse from RAG");
                            }
                            return body;
                        }, (RetryContext recoveryCtx) -> {
                            log.warn("RAG call permanently failed after {} attempts for caseId={}, docId={}, queryId={}",
                                    recoveryCtx.getRetryCount(), caseId, docId, queryId, recoveryCtx.getLastThrowable());
                            failedBatch.add(buildReservationParams(caseId, queryId, docId));
                            return null;
                        });
                log.info("RAG call duration for queryId={}: {} ms", queryId, System.currentTimeMillis() - started);

                if (resp == null) {
                    continue;
                }

                final String llmInputJson;
                try {
                    reusablePayload.clear();
                    final List<Object> chunks = Optional.ofNullable(resp.getChunkedEntries())
                            .orElseGet(Collections::emptyList);
                    reusablePayload.put("provenanceChunksSample", chunks);
                    llmInputJson = objectMapper.writeValueAsString(reusablePayload);
                } catch (final Exception e) {
                    log.warn("Failed to build llm_input JSON for caseId={}, docId={}, queryId={}: {}",
                            caseId, docId, queryId, e.getMessage(), e);
                    failedBatch.add(buildReservationParams(caseId, queryId, docId));
                    continue;
                }

                answersBatch.add(buildAnswerParams(caseId, queryId, plan.version, resp.getLlmResponse(), llmInputJson, docId));
                doneBatch.add(buildReservationParams(caseId, queryId, docId));
            }
        }

        if (proceed) {
            transactionTemplate.execute(status -> {
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
        }

        if (proceed) {
            log.info("GenerateAnswersTasklet finished: {} done, {} failed (caseId={}, docId={}, materialId={})",
                    doneBatch.size(), failedBatch.size(), caseId, docId, materialIdStr);
        }

        return RepeatStatus.FINISHED;
    }

    private static final class WorkPlan {
        private final int version;
        private final boolean doWork;

        private WorkPlan(final Integer version, final boolean doWork) {
            this.version = version == null ? 1 : version;
            this.doWork = doWork;
        }
    }
}
