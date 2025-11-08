package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_UPLOAD_VERIFIED_KEY;

import uk.gov.hmcts.cp.cdk.batch.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReserveAnswerVersionTasklet implements Tasklet {

    private final QueryResolver queryResolver;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final DocumentIdResolver documentIdResolver;

    @Override
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.OnlyOneReturn", "ignoreElseIf"})
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final RepeatStatus status = RepeatStatus.FINISHED;

        final StepExecution stepExecution = contribution != null ? contribution.getStepExecution() : null;
        if (stepExecution == null) {
            log.warn("ReserveAnswerVersionTasklet: StepExecution is null; finishing with no work.");
            return status;
        }

        final ExecutionContext stepCtx = stepExecution.getExecutionContext();
        final JobExecution jobExecution = stepExecution.getJobExecution();
        final ExecutionContext jobCtx = jobExecution != null ? jobExecution.getExecutionContext() : new ExecutionContext();

        // ---- Read IDs from contexts -----------------------------------------------------------
        final String caseIdStr = firstNonNull(getOrNull(stepCtx, CTX_CASE_ID_KEY), getOrNull(jobCtx, CTX_CASE_ID_KEY));
        final String docIdStrCtx = firstNonNull(getOrNull(stepCtx, CTX_DOC_ID_KEY), getOrNull(jobCtx, CTX_DOC_ID_KEY));
        final String materialIdStr = firstNonNull(getOrNull(stepCtx, CTX_MATERIAL_ID_KEY), getOrNull(jobCtx, CTX_MATERIAL_ID_KEY));

        if (caseIdStr == null) {
            log.debug("ReserveAnswerVersionTasklet: Missing caseId in context; skipping.");
            return status;
        }

        final UUID caseId;
        try {
            caseId = UUID.fromString(caseIdStr);
        } catch (IllegalArgumentException ex) {
            log.warn("ReserveAnswerVersionTasklet: Invalid caseId='{}' — skipping.", caseIdStr);
            return status;
        }

        UUID docId = null;
        if (docIdStrCtx != null) {
            try {
                docId = UUID.fromString(docIdStrCtx);
            } catch (IllegalArgumentException ex) {
                log.debug("ReserveAnswerVersionTasklet: ignoring invalid docId in context: '{}'", docIdStrCtx);
            }
        }

        // Prefer DB docId if (caseId, materialId) exists
        if (materialIdStr != null) {
            try {
                final UUID materialId = UUID.fromString(materialIdStr);
                final Optional<UUID> existing = documentIdResolver.resolveExistingDocId(caseId, materialId);
                if (existing.isPresent() && !existing.get().equals(docId)) {
                    log.info("ReserveAnswerVersionTasklet: overriding docId from context {} -> {} based on DB for caseId={}, materialId={}",
                            docId, existing.get(), caseId, materialId);
                    docId = existing.get();
                    stepCtx.putString(CTX_DOC_ID_KEY, docId.toString()); // keep downstream in sync
                }
            } catch (IllegalArgumentException ex) {
                log.debug("ReserveAnswerVersionTasklet: invalid materialId='{}' — continuing with context docId.", materialIdStr);
            }
        }

        if (docId == null) {
            log.debug("ReserveAnswerVersionTasklet: No docId available after DB resolution; skipping.");
            return status;
        }

        // Make final copies for lambdas
        final UUID resolvedCaseId = caseId;
        final UUID resolvedDocId = docId;

        // ---- Verify ingestion success flag for this docId -------------------------------------
        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + resolvedDocId;
        final Boolean jobVerified = (Boolean) jobCtx.get(verifiedKey);
        if (!Boolean.TRUE.equals(jobVerified)) {
            log.info("ReserveAnswerVersionTasklet: ingestion not verified as SUCCESS for docId={}; skipping reservations.", resolvedDocId);
            return status;
        }

        // ---- Ensure the document is present in case_documents ---------------------------------
        final MapSqlParameterSource checkParams = new MapSqlParameterSource()
                .addValue("case_id", resolvedCaseId)
                .addValue("doc_id", resolvedDocId);

        final Boolean documentExists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM case_documents WHERE case_id=:case_id AND doc_id=:doc_id)",
                checkParams,
                Boolean.class
        ));

        if (!documentExists) {
            log.warn("ReserveAnswerVersionTasklet: case_documents missing doc_id={} for case_id={}; skipping reservations.", resolvedDocId, resolvedCaseId);
            return status;
        }

        // ---- Resolve queries ------------------------------------------------------------------
        final List<Query> queries = queryResolver.resolve();
        if (queries == null || queries.isEmpty()) {
            log.debug("ReserveAnswerVersionTasklet: No queries resolved; nothing to reserve.");
            return status;
        }

        final Set<UUID> queryIds = queries.stream()
                .map(Query::getQueryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (queryIds.isEmpty()) {
            log.debug("ReserveAnswerVersionTasklet: All resolved queries had null IDs; nothing to reserve.");
            return status;
        }

        // ---- Call get_or_create_answer_version for each query --------------------------------
        final StringBuilder values = new StringBuilder();
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("case_id", resolvedCaseId)
                .addValue("doc_id", resolvedDocId);

        int paramIndex = 0;
        for (final UUID qid : queryIds) {
            if (paramIndex > 0) {
                values.append(", ");
            }
            values.append("(:q").append(paramIndex).append("::uuid)");
            params.addValue("q" + paramIndex, qid);
            paramIndex++;
        }

        final String sql =
                "WITH q(id) AS (VALUES " + values + ") " +
                        "SELECT id AS query_id, get_or_create_answer_version(:case_id, id, :doc_id) AS version FROM q";

        final TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        transactionTemplate.execute(txStatus -> {
            final List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
            if (rows.isEmpty()) {
                log.debug("ReserveAnswerVersionTasklet: get_or_create_answer_version returned no rows; nothing to update.");
                return null;
            }

            final MapSqlParameterSource[] batch = new MapSqlParameterSource[rows.size()];
            int rowIndex = 0;
            for (final Map<String, Object> row : rows) {
                final UUID queryId = (UUID) row.get("query_id");
                final int version = ((Number) row.get("version")).intValue();
                batch[rowIndex++] = new MapSqlParameterSource()
                        .addValue("case_id", resolvedCaseId)
                        .addValue("query_id", queryId)
                        .addValue("doc_id", resolvedDocId)
                        .addValue("version", version);
            }

            jdbc.batchUpdate(
                    "UPDATE answer_reservations " +
                            "   SET updated_at = NOW() " +
                            " WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id AND version=:version",
                    batch
            );
            return null;
        });

        return status;
    }

    @SuppressWarnings({"PMD.OnlyOneReturn", "ignoreElseIf"})
    private static String getOrNull(final ExecutionContext ctx, final String key) {
        if (ctx == null || key == null) {
            return null;
        }
        return ctx.containsKey(key) ? ctx.getString(key) : null;
    }

    private static String firstNonNull(final String first, final String second) {
        return first != null ? first : second;
    }
}
