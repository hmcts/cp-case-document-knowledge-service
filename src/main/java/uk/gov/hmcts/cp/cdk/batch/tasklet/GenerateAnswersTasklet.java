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
import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID;

@Component
@RequiredArgsConstructor
public class GenerateAnswersTasklet implements Tasklet {
    private final QueryResolver queryResolver;
    private final QueryDefinitionLatestRepository qdlRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final String caseIdStr = stepCtx.getString("caseId", null);
        final String docIdStr = stepCtx.getString(CTX_DOC_ID, null);
        if (caseIdStr == null || docIdStr == null) return RepeatStatus.FINISHED;

        final UUID caseId = UUID.fromString(caseIdStr);
        final UUID docId = UUID.fromString(docIdStr);

        final List<Query> queries = queryResolver.resolve();
        if (queries.isEmpty()) return RepeatStatus.FINISHED;

        for (final Query q : queries) {
            final UUID queryId = q.getQueryId();

            // TX 1: claim reservation and get version
            final TransactionTemplate tt1 = new TransactionTemplate(txManager);
            tt1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            final Integer version = tt1.execute(status -> {
                Integer v = jdbc.queryForObject(
                        "SELECT version FROM answer_reservations " +
                                "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("case_id", caseId)
                                .addValue("query_id", queryId)
                                .addValue("doc_id", docId),
                        Integer.class
                );
                if (v == null) {
                    v = jdbc.queryForObject(
                            "SELECT get_or_create_answer_version(:case_id,:query_id,:doc_id)",
                            new MapSqlParameterSource()
                                    .addValue("case_id", caseId)
                                    .addValue("query_id", queryId)
                                    .addValue("doc_id", docId),
                            Integer.class
                    );
                }
                jdbc.update(
                        "UPDATE answer_reservations SET status='IN_PROGRESS', updated_at = NOW() " +
                                "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id " +
                                "AND status IN ('NEW','FAILED')",
                        new MapSqlParameterSource()
                                .addValue("case_id", caseId)
                                .addValue("query_id", queryId)
                                .addValue("doc_id", docId)
                );
                return v;
            });

            // IO/compute outside tx
            final Optional<QueryDefinitionLatest> def = qdlRepo.findByQueryId(queryId);
            final String prompt = def.map(QueryDefinitionLatest::getQueryPrompt).orElse("");
            final String userQuery = def.map(QueryDefinitionLatest::getUserQuery).orElse("");

            final String answerText = "[auto/batch] Answer for " + userQuery + " with prompt: " + prompt;
            final String llmInput = "{\"prompt\":\"" + prompt.replace("\"", "\\\"") + "\"}";

            // TX 2: upsert answer and mark DONE
            final TransactionTemplate tt2 = new TransactionTemplate(txManager);
            tt2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            tt2.execute(status -> {
                jdbc.update(
                        "INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) " +
                                "VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) " +
                                "ON CONFLICT (case_id, query_id, version) DO UPDATE SET " +
                                "answer = EXCLUDED.answer, llm_input = EXCLUDED.llm_input, doc_id = EXCLUDED.doc_id, " +
                                "created_at = EXCLUDED.created_at",
                        new MapSqlParameterSource()
                                .addValue("case_id", caseId)
                                .addValue("query_id", queryId)
                                .addValue("version", version)
                                .addValue("answer", answerText)
                                .addValue("llm_input", llmInput)
                                .addValue("doc_id", docId)
                );
                jdbc.update(
                        "UPDATE answer_reservations SET status='DONE', updated_at = NOW() " +
                                "WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id",
                        new MapSqlParameterSource()
                                .addValue("case_id", caseId)
                                .addValue("query_id", queryId)
                                .addValue("doc_id", docId)
                );
                return null;
            });
        }
        return RepeatStatus.FINISHED;
    }
}

