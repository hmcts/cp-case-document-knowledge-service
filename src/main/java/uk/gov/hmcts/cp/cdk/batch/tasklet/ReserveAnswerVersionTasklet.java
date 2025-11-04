package uk.gov.hmcts.cp.cdk.batch.tasklet;

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
import uk.gov.hmcts.cp.cdk.batch.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.stream.Collectors;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_UPLOAD_VERIFIED_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReserveAnswerVersionTasklet implements Tasklet {

    private final QueryResolver queryResolver;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    @Override
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.OnlyOneReturn","ignoreElseIf"})
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final RepeatStatus status = RepeatStatus.FINISHED;
        boolean proceed = true;

        final StepExecution stepExecution = contribution != null ? contribution.getStepExecution() : null;
        if (stepExecution == null) {
            log.warn("ReserveAnswerVersionTasklet: StepExecution is null; finishing with no work.");
            proceed = false;
        }

        ExecutionContext stepCtx = null;
        ExecutionContext jobCtx = null;
        if (proceed) {
            stepCtx = stepExecution.getExecutionContext();
            final JobExecution jobExecution = stepExecution.getJobExecution();
            jobCtx = jobExecution != null ? jobExecution.getExecutionContext() : new ExecutionContext();
        }

        if (proceed) {
            final String docId = stepCtx.getString(CTX_DOC_ID_KEY, null);
            final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
            final Boolean jobVerified  = (Boolean) jobCtx.get(verifiedKey);
            if (!Boolean.TRUE.equals(jobVerified)) {
                log.info("ReserveAnswerVersionTasklet: ingestion not verified as SUCCESS; skipping reservations.");
                proceed = false;
            }
        }

        UUID caseId = null;
        UUID docId = null;
        if (proceed) {
            final String caseIdStr = firstNonNull(
                    getOrNull(stepCtx, CTX_CASE_ID_KEY),
                    getOrNull(jobCtx, CTX_CASE_ID_KEY)
            );
            final String docIdStr = firstNonNull(
                    getOrNull(stepCtx, CTX_DOC_ID_KEY),
                    getOrNull(jobCtx, CTX_DOC_ID_KEY)
            );

            if (caseIdStr == null || docIdStr == null) {
                log.debug("ReserveAnswerVersionTasklet: Missing caseId/docId in context; caseId={}, docId={}", caseIdStr, docIdStr);
                proceed = false;
            } else {
                try {
                    caseId = UUID.fromString(caseIdStr);
                    docId  = UUID.fromString(docIdStr);
                } catch (IllegalArgumentException ex) {
                    log.warn("ReserveAnswerVersionTasklet: Invalid UUIDs in context. caseId='{}', docId='{}' — skipping.", caseIdStr, docIdStr);
                    proceed = false;
                }
            }
        }

        if (proceed) {
            final MapSqlParameterSource checkParams = new MapSqlParameterSource()
                    .addValue("case_id", caseId)
                    .addValue("doc_id", docId);

            final Boolean documentExists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM case_documents WHERE case_id=:case_id AND doc_id=:doc_id)",
                    checkParams,
                    Boolean.class
            ));

            if (!documentExists) {
                log.warn("ReserveAnswerVersionTasklet: case_documents missing doc_id={} for case_id={}; skipping reservations.", docId, caseId);
                proceed = false;
            }
        }

        List<UUID> queryIdList = List.of();
        if (proceed) {
            final List<Query> queries = queryResolver.resolve();
            if (queries == null || queries.isEmpty()) {
                log.debug("ReserveAnswerVersionTasklet: No queries resolved; nothing to reserve.");
                proceed = false;
            } else {
                final Set<UUID> queryIds = queries.stream()
                        .map(Query::getQueryId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                if (queryIds.isEmpty()) {
                    log.debug("ReserveAnswerVersionTasklet: All resolved queries had null IDs; nothing to reserve.");
                    proceed = false;
                } else {
                    queryIdList = new ArrayList<>(queryIds);
                }
            }
        }

        if (proceed) {
            final StringJoiner joiner = new StringJoiner(", ");
            final MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("case_id", caseId)
                    .addValue("doc_id", docId);

            for (int index = 0; index < queryIdList.size(); index++) {
                joiner.add("(:q" + index + "::uuid)");
                params.addValue("q" + index, queryIdList.get(index));
            }

            final String sql =
                    "WITH q(id) AS (VALUES " + joiner + ") " +
                            "SELECT id AS query_id, get_or_create_answer_version(:case_id, id, :doc_id) AS version FROM q";

            final TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
            transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

            final String finalSql = sql;
            final MapSqlParameterSource finalParams = params;
            final UUID finalCaseId = caseId;
            final UUID finalDocId = docId;

            transactionTemplate.execute(txStatus -> {
                final List<Map<String, Object>> rows = jdbc.queryForList(finalSql, finalParams);
                if (!rows.isEmpty()) {// NOPMD - needed to handle empty rows
                    final MapSqlParameterSource[] batch = new MapSqlParameterSource[rows.size()];
                    int index = 0;
                    for (final Map<String, Object> row : rows) {
                        final UUID queryId = (UUID) row.get("query_id");
                        final int version = ((Number) row.get("version")).intValue();
                        batch[index++] = new MapSqlParameterSource()
                                .addValue("case_id", finalCaseId)
                                .addValue("query_id", queryId)
                                .addValue("doc_id", finalDocId)
                                .addValue("version", version);
                    }

                    jdbc.batchUpdate(
                            "UPDATE answer_reservations " +
                                    "   SET updated_at = NOW() " +
                                    " WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id AND version=:version",
                            batch
                    );
                } else {
                    log.debug("ReserveAnswerVersionTasklet: get_or_create_answer_version returned no rows; nothing to update.");
                }
                return null;
            });
        }

        return status;
    }

    @SuppressWarnings({"PMD.OnlyOneReturn","ignoreElseIf"})
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
