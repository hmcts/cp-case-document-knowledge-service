package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
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

import java.util.*;
import java.util.stream.Collectors;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReserveAnswerVersionTasklet implements Tasklet {

    private final QueryResolver queryResolver;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final String caseIdStr = stepCtx.getString("caseId", null);
        final String docIdStr = stepCtx.getString(CTX_DOC_ID_KEY, null);

        if (caseIdStr != null && docIdStr != null) {
            final UUID caseId = UUID.fromString(caseIdStr);
            final UUID docId = UUID.fromString(docIdStr);

            final List<Query> queries = queryResolver.resolve();
            if (queries != null && !queries.isEmpty()) {
                final Set<UUID> queryIds = queries.stream()
                        .map(Query::getQueryId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                if (!queryIds.isEmpty()) { // NOPMD: AvoidUsingIfElseWithNegation
                    final List<UUID> ids = new ArrayList<>(queryIds);
                    final StringJoiner joiner = new StringJoiner(", ");
                    final MapSqlParameterSource params = new MapSqlParameterSource()
                            .addValue("case_id", caseId)
                            .addValue("doc_id", docId);

                    for (int idx = 0; idx < ids.size(); idx++) {
                        joiner.add("(:q" + idx + "::uuid)");
                        params.addValue("q" + idx, ids.get(idx));
                    }

                    final String sql =
                            "WITH q(id) AS (VALUES " + joiner + ") " +
                                    "SELECT id AS query_id, get_or_create_answer_version(:case_id, id, :doc_id) AS version FROM q";

                    final TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
                    transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

                    transactionTemplate.execute(status -> {
                        final List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
                        if (!rows.isEmpty()) {
                            final MapSqlParameterSource[] batch = new MapSqlParameterSource[rows.size()];
                            int idx = 0;
                            for (final Map<String, Object> row : rows) {
                                final UUID qid = (UUID) row.get("query_id");
                                final int version = ((Number) row.get("version")).intValue();
                                batch[idx++] = new MapSqlParameterSource()
                                        .addValue("case_id", caseId)
                                        .addValue("query_id", qid)
                                        .addValue("doc_id", docId)
                                        .addValue("version", version);
                            }

                            jdbc.batchUpdate(
                                    "UPDATE answer_reservations SET updated_at = NOW() " +
                                            "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id AND version=:version",
                                    batch
                            );
                        }
                        return null;
                    });
                } else {
                    log.debug("All resolved queries had null IDs; nothing to reserve.");
                }
            } else {
                log.debug("No queries resolved; nothing to reserve.");
            }
        } else {
            log.debug("Missing caseId/docId in step context; nothing to reserve.");
        }

        return RepeatStatus.FINISHED;
    }
}