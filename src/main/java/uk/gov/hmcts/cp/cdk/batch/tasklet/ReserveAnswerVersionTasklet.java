package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID;

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
        final String docIdStr  = stepCtx.getString(CTX_DOC_ID, null);
        if (caseIdStr == null || docIdStr == null) return RepeatStatus.FINISHED;

        final UUID caseId = UUID.fromString(caseIdStr);
        final UUID docId  = UUID.fromString(docIdStr);

        final List<Query> queries = queryResolver.resolve();
        if (queries.isEmpty()) return RepeatStatus.FINISHED;

        final TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        tt.execute(status -> {
            for (final Query q : queries) {
                final Integer version = jdbc.queryForObject(
                        "SELECT get_or_create_answer_version(:case_id, :query_id, :doc_id)",
                        new MapSqlParameterSource()
                                .addValue("case_id", caseId)
                                .addValue("query_id", q.getQueryId())
                                .addValue("doc_id", docId),
                        Integer.class
                );
                jdbc.update(
                        "UPDATE answer_reservations SET updated_at = NOW() " +
                                "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id AND version=:version",
                        new MapSqlParameterSource()
                                .addValue("case_id", caseId)
                                .addValue("query_id", q.getQueryId())
                                .addValue("doc_id", docId)
                                .addValue("version", version)
                );
            }
            return null;
        });
        return RepeatStatus.FINISHED;
    }
}

