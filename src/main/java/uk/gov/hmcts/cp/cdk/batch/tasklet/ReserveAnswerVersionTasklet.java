package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_UPLOAD_VERIFIED_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskLookupUtils.parseUuidOrNull;

import uk.gov.hmcts.cp.cdk.batch.support.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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

    private static final String SQL_EXISTING_RESERVATIONS =
            "SELECT query_id FROM answer_reservations WHERE case_id=:case_id AND doc_id=:doc_id";

    private final QueryResolver queryResolver;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final RepeatStatus result = RepeatStatus.FINISHED;
        boolean proceed = true;

        final StepExecution stepExecution = contribution != null ? contribution.getStepExecution() : null;
        if (stepExecution == null) {
            log.warn("ReserveAnswerVersionTasklet: StepExecution is null; finishing with no work.");
            proceed = false;
        }

        final ExecutionContext stepCtx;
        final JobExecution jobExecution;
        final ExecutionContext jobCtx;
        if (proceed) {
            stepCtx = stepExecution.getExecutionContext();
            jobExecution = stepExecution.getJobExecution();
            jobCtx = jobExecution != null ? jobExecution.getExecutionContext() : new ExecutionContext();
        } else {
            stepCtx = null;
            jobCtx = null;
        }

        final String caseIdStr;
        final String docIdStr;
        final String materialIdStr;
        if (proceed) {
            caseIdStr = getString(stepCtx, jobCtx, CTX_CASE_ID_KEY);
            docIdStr = getString(stepCtx, jobCtx, CTX_DOC_ID_KEY);
            materialIdStr = getString(stepCtx, jobCtx, CTX_MATERIAL_ID_KEY);
            if (caseIdStr == null || docIdStr == null) {
                log.debug("ReserveAnswerVersionTasklet: Missing caseId/docId in context; skipping.");
                proceed = false;
            }
        } else {
            caseIdStr = null;
            docIdStr = null;
            materialIdStr = null;
        }

        final UUID caseId;
        final UUID docId;
        if (proceed) {
            caseId = parseUuidOrNull(caseIdStr);
            docId = parseUuidOrNull(docIdStr);
            if (caseId == null || docId == null) {
                proceed = false;
            }
        } else {
            caseId = null;
            docId = null;
        }

        if (proceed) {
            final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
            final Boolean jobVerified = jobCtx != null ? (Boolean) jobCtx.get(verifiedKey) : null;
            if (!Boolean.TRUE.equals(jobVerified)) {
                log.info("ReserveAnswerVersionTasklet: ingestion not verified as SUCCESS for docId={}; skipping.", docId);
                proceed = false;
            }
        }

        final MapSqlParameterSource checkParams;
        if (proceed) {
            checkParams = new MapSqlParameterSource()
                    .addValue("case_id", caseId)
                    .addValue("doc_id", docId);
            final Boolean documentExists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM case_documents WHERE case_id=:case_id AND doc_id=:doc_id)",
                    checkParams, Boolean.class));
            if (!documentExists) {
                log.warn("ReserveAnswerVersionTasklet: case_documents missing doc_id={} for case_id={}; skipping.", docId, caseId);
                proceed = false;
            }
        } else {
            checkParams = null;
        }

        final Set<UUID> candidateQueryIds;
        if (proceed) {
            final List<Query> queries = queryResolver.resolve();
            if (queries == null || queries.isEmpty()) {
                log.debug("ReserveAnswerVersionTasklet: No queries resolved; nothing to reserve.");
                proceed = false;
                candidateQueryIds = new LinkedHashSet<>();
            } else {
                candidateQueryIds = queries.stream()
                        .map(Query::getQueryId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (candidateQueryIds.isEmpty()) {
                    log.debug("ReserveAnswerVersionTasklet: All resolved queries had null IDs; nothing to reserve.");
                    proceed = false;
                }
            }
        } else {
            candidateQueryIds = new LinkedHashSet<>();
        }

        final Set<UUID> toReserve;
        if (proceed) {
            final List<UUID> existing = jdbc.query(SQL_EXISTING_RESERVATIONS, checkParams,
                    (rs, rowNum) -> rs.getObject("query_id", UUID.class));
            final Set<UUID> existingSet = new LinkedHashSet<>(existing);
            toReserve = candidateQueryIds.stream()
                    .filter(q -> !existingSet.contains(q))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (toReserve.isEmpty()) {
                log.info("ReserveAnswerVersionTasklet: All {} queries already reserved (caseId={}, docId={}, materialId={}).",
                        candidateQueryIds.size(), caseId, docId, materialIdStr);
                proceed = false;
            }
        } else {
            toReserve = new LinkedHashSet<>();
        }

        if (proceed) {
            final StringBuilder values = new StringBuilder();
            final MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("case_id", caseId)
                    .addValue("doc_id", docId);

            int paramIndex = 0;
            for (final UUID queryId : toReserve) {
                if (paramIndex > 0) {
                    values.append(", ");
                }
                values.append("(:q").append(paramIndex).append("::uuid)");
                params.addValue("q" + paramIndex, queryId);
                paramIndex++;
            }

            final String sql =
                    "WITH q(id) AS (VALUES " + values + ") " +
                            "SELECT id AS query_id, get_or_create_answer_version(:case_id, id, :doc_id) AS version FROM q";

            final TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
            transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            transactionTemplate.execute(txStatus -> {
                jdbc.queryForList(sql, params);
                return null;
            });

            log.info("ReserveAnswerVersionTasklet: reserved/confirmed versions for {} queries (caseId={}, docId={}, materialId={}).",
                    toReserve.size(), caseId, docId, materialIdStr);
        }

        return result;
    }

    private static String getString(final ExecutionContext stepCtx, final ExecutionContext jobCtx, final String key) {
        String value = null;
        if (stepCtx != null && stepCtx.containsKey(key)) {
            value = stepCtx.getString(key);
        } else if (jobCtx != null && jobCtx.containsKey(key)) {
            value = jobCtx.getString(key);
        }
        return value;
    }
}
