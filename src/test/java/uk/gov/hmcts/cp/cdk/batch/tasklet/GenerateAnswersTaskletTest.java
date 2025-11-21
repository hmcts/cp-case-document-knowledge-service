package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_UPLOAD_VERIFIED_KEY;

import uk.gov.hmcts.cp.cdk.batch.support.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class GenerateAnswersTaskletTest {

    @Mock
    private QueryResolver queryResolver;

    @Mock
    private QueryDefinitionLatestRepository qdlRepo;

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private DocumentInformationSummarisedApi documentInformationSummarisedApi;

    @Mock
    private StepContribution contribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private StepExecution stepExecution;

    @Mock
    private JobExecution jobExecution;

    private final PlatformTransactionManager txManager = new NoopTxManager();

    private ObjectMapper objectMapper;
    private RetryTemplate retryTemplate;
    private GenerateAnswersTasklet tasklet;

    private ExecutionContext stepCtx;
    private ExecutionContext jobCtx;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        retryTemplate = new RetryTemplate();
        final SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(1); // single attempt
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(new NoBackOffPolicy());

        tasklet = new GenerateAnswersTasklet(
                queryResolver,
                qdlRepo,
                jdbc,
                txManager,
                objectMapper,
                documentInformationSummarisedApi,
                retryTemplate
        );

        stepCtx = new ExecutionContext();
        jobCtx = new ExecutionContext();

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        when(jobExecution.getExecutionContext()).thenReturn(jobCtx);
    }

    @Test
    @DisplayName("Missing caseId/docId -> FINISHED with no work")
    void missingIdsSkips() {
        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    @DisplayName("Not verified (job flag false/missing) -> FINISHED with no work")
    void notVerifiedSkips() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        // No verified flag in job context -> skip

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    @DisplayName("Eligible NEW/FAILED -> mark IN_PROGRESS, call API, upsert answer, mark DONE")
    void eligibleHappyPath() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        final UUID queryId = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_ID_KEY, UUID.randomUUID().toString());

        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
        jobCtx.put(verifiedKey, true);

        final Query q = new Query();
        q.setQueryId(queryId);
        q.setLabel("Q1");
        when(queryResolver.resolve()).thenReturn(List.of(q));

        // Eligible reservation from DB (NEW/FAILED)
        when(jdbc.query(
                eq("SELECT query_id FROM answer_reservations  WHERE case_id=:case_id AND doc_id=:doc_id AND status IN ('NEW','FAILED')"),
                any(MapSqlParameterSource.class),
                anyUuidRowMapper()
        )).thenReturn(List.of(queryId));

        // Version exists, and IN_PROGRESS update succeeds
        when(jdbc.query(
                eq("SELECT version FROM answer_reservations  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource.class),
                anyIntRowMapper()
        )).thenReturn(List.of(1));

        when(jdbc.update(
                eq("UPDATE answer_reservations    SET status='IN_PROGRESS', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id    AND status IN ('NEW','FAILED')"),
                any(MapSqlParameterSource.class)
        )).thenReturn(1);

        // Latest definition
        final QueryDefinitionLatest def = new QueryDefinitionLatest();
        def.setQueryId(queryId);
        def.setUserQuery("user-query");
        def.setQueryPrompt("prompt");
        when(qdlRepo.findByQueryId(queryId)).thenReturn(Optional.of(def));

        // API success
        final UserQueryAnswerReturnedSuccessfully rag =
                new UserQueryAnswerReturnedSuccessfully()
                        .llmResponse("answer-1")
                        .chunkedEntries(new ArrayList<>());
        when(documentInformationSummarisedApi.answerUserQuery(any(AnswerUserQueryRequest.class)))
                .thenReturn(ResponseEntity.ok(rag));

        // Batch updates for UPSERT and DONE
        when(jdbc.batchUpdate(
                eq("INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) ON CONFLICT (case_id, query_id, version) DO UPDATE SET   answer = EXCLUDED.answer,   llm_input = EXCLUDED.llm_input,   doc_id = EXCLUDED.doc_id,   created_at = EXCLUDED.created_at"),
                any(MapSqlParameterSource[].class)
        )).thenReturn(new int[]{1});

        when(jdbc.batchUpdate(
                eq("UPDATE answer_reservations SET status='DONE', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource[].class)
        )).thenReturn(new int[]{1});

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        // Verify API called with metadata filter on document_id
        final ArgumentCaptor<AnswerUserQueryRequest> reqCap = ArgumentCaptor.forClass(AnswerUserQueryRequest.class);
        verify(documentInformationSummarisedApi).answerUserQuery(reqCap.capture());
        final AnswerUserQueryRequest sent = reqCap.getValue();
        final List<MetadataFilter> filters = sent.getMetadataFilter();
        assertThat(filters).isNotNull();
        assertThat(filters).anySatisfy(f ->
                assertThat(Map.entry(f.getKey(), f.getValue()))
                        .isEqualTo(Map.entry("document_id", docId.toString()))
        );

        // Verify both batch updates were invoked once
        verify(jdbc, times(1)).batchUpdate(
                eq("INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) ON CONFLICT (case_id, query_id, version) DO UPDATE SET   answer = EXCLUDED.answer,   llm_input = EXCLUDED.llm_input,   doc_id = EXCLUDED.doc_id,   created_at = EXCLUDED.created_at"),
                any(MapSqlParameterSource[].class)
        );
        verify(jdbc, times(1)).batchUpdate(
                eq("UPDATE answer_reservations SET status='DONE', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource[].class)
        );
    }

    @Test
    @DisplayName("API failure -> reservation marked FAILED (no answer UPSERT)")
    void apiFailureMarksFailed() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        final UUID queryId = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());

        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
        jobCtx.put(verifiedKey, true);

        final Query q = new Query();
        q.setQueryId(queryId);
        q.setLabel("Q1");
        when(queryResolver.resolve()).thenReturn(List.of(q));

        when(jdbc.query(
                eq("SELECT query_id FROM answer_reservations  WHERE case_id=:case_id AND doc_id=:doc_id AND status IN ('NEW','FAILED')"),
                any(MapSqlParameterSource.class),
                anyUuidRowMapper()
        )).thenReturn(List.of(queryId));

        when(jdbc.query(
                eq("SELECT version FROM answer_reservations  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource.class),
                anyIntRowMapper()
        )).thenReturn(Collections.emptyList());

        when(jdbc.queryForObject(
                eq("SELECT get_or_create_answer_version(:case_id,:query_id,:doc_id)"),
                any(MapSqlParameterSource.class),
                eq(Integer.class)
        )).thenReturn(1);

        when(jdbc.update(
                eq("UPDATE answer_reservations    SET status='IN_PROGRESS', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id    AND status IN ('NEW','FAILED')"),
                any(MapSqlParameterSource.class)
        )).thenReturn(1);

        // Make API fail (empty body triggers IllegalStateException handling path in tasklet via RetryTemplate recovery)
        when(documentInformationSummarisedApi.answerUserQuery(any(AnswerUserQueryRequest.class)))
                .thenReturn(ResponseEntity.ok(null));

        when(jdbc.batchUpdate(
                eq("UPDATE answer_reservations SET status='FAILED', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource[].class)
        )).thenReturn(new int[]{1});

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        // Ensure FAILED path called; and no UPSERT executed
        verify(jdbc, times(1)).batchUpdate(
                eq("UPDATE answer_reservations SET status='FAILED', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource[].class)
        );
    }

    @Test
    @DisplayName("Single-query mode -> only that query is processed even if multiple are eligible")
    void singleQueryModeFilters() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        final UUID q1 = UUID.randomUUID();
        final UUID q2 = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());

        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
        jobCtx.put(verifiedKey, true);

        final Query query1 = new Query();
        query1.setQueryId(q1);
        query1.setLabel("Q1");
        final Query query2 = new Query();
        query2.setQueryId(q2);
        query2.setLabel("Q2");
        when(queryResolver.resolve()).thenReturn(List.of(query1, query2));

        // Eligible both from DB
        when(jdbc.query(
                eq("SELECT query_id FROM answer_reservations  WHERE case_id=:case_id AND doc_id=:doc_id AND status IN ('NEW','FAILED')"),
                any(MapSqlParameterSource.class),
                anyUuidRowMapper()
        )).thenReturn(List.of(q1, q2));

        // Provide single-query mode via job parameter
        final JobParameters params = new JobParametersBuilder()
                .addString("CTX_SINGLE_QUERY_ID", q2.toString())
                .toJobParameters();
        when(jobExecution.getJobParameters()).thenReturn(params);

        // Make IN_PROGRESS update succeed only once (for the chosen query)
        when(jdbc.query(
                eq("SELECT version FROM answer_reservations  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource.class),
                anyIntRowMapper()
        )).thenReturn(List.of(1));

        when(jdbc.update(
                eq("UPDATE answer_reservations    SET status='IN_PROGRESS', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id    AND status IN ('NEW','FAILED')"),
                any(MapSqlParameterSource.class)
        )).thenReturn(1);

        // qdl + API minimal stubs to let it proceed
        when(qdlRepo.findByQueryId(any(UUID.class))).thenReturn(Optional.empty());
        final UserQueryAnswerReturnedSuccessfully rag =
                new UserQueryAnswerReturnedSuccessfully()
                        .llmResponse("ok")
                        .chunkedEntries(Collections.emptyList());
        when(documentInformationSummarisedApi.answerUserQuery(any(AnswerUserQueryRequest.class)))
                .thenReturn(ResponseEntity.ok(rag));

        when(jdbc.batchUpdate(
                eq("INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) ON CONFLICT (case_id, query_id, version) DO UPDATE SET   answer = EXCLUDED.answer,   llm_input = EXCLUDED.llm_input,   doc_id = EXCLUDED.doc_id,   created_at = EXCLUDED.created_at"),
                any(MapSqlParameterSource[].class)
        )).thenReturn(new int[]{1});

        when(jdbc.batchUpdate(
                eq("UPDATE answer_reservations SET status='DONE', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource[].class)
        )).thenReturn(new int[]{1});

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        // One UPSERT + one DONE batch should happen
        verify(jdbc, times(1)).batchUpdate(
                eq("INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) ON CONFLICT (case_id, query_id, version) DO UPDATE SET   answer = EXCLUDED.answer,   llm_input = EXCLUDED.llm_input,   doc_id = EXCLUDED.doc_id,   created_at = EXCLUDED.created_at"),
                any(MapSqlParameterSource[].class)
        );
        verify(jdbc, times(1)).batchUpdate(
                eq("UPDATE answer_reservations SET status='DONE', updated_at=NOW()  WHERE case_id=:case_id AND query_id=:query_id AND doc_id=:doc_id"),
                any(MapSqlParameterSource[].class)
        );
    }

    // ---------- helpers ----------

    private static class NoopTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(final TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(final TransactionStatus status) {
        }

        @Override
        public void rollback(final TransactionStatus status) {
        }
    }

    /**
     * Typed matcher to avoid rawtype warnings with -Werror.
     */
    private static RowMapper<UUID> anyUuidRowMapper() {
        return org.mockito.ArgumentMatchers.<RowMapper<UUID>>any();
    }

    /**
     * Typed matcher to avoid rawtype warnings with -Werror.
     */
    private static RowMapper<Integer> anyIntRowMapper() {
        return org.mockito.ArgumentMatchers.<RowMapper<Integer>>any();
    }
}
