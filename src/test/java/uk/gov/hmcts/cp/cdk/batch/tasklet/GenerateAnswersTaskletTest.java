package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gov.hmcts.cp.cdk.batch.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID;

@DisplayName("GenerateAnswersTasklet tests")
@ExtendWith(MockitoExtension.class)
class GenerateAnswersTaskletTest {

    @Mock private QueryResolver queryResolver;
    @Mock private QueryDefinitionLatestRepository qdlRepo;
    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private DocumentInformationSummarisedApi documentInformationSummarisedApi;

    private final PlatformTransactionManager txManager = new NoopTxManager();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private StepContribution contribution;
    @Mock private ChunkContext chunkContext;
    @Mock private StepExecution stepExecution;

    static class NoopTxManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }

    private GenerateAnswersTasklet newTasklet() {
        return new GenerateAnswersTasklet(
                queryResolver, qdlRepo, jdbc, txManager, objectMapper, documentInformationSummarisedApi);
    }

    @Test
    @DisplayName("Processes multiple queries: reserves versions, marks IN_PROGRESS, calls RAG, upserts answers, marks DONE")
    void processesMultipleQueriesWithBatchWrites() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final UUID docId  = UUID.randomUUID();
        ExecutionContext stepCtx = new ExecutionContext();
        stepCtx.putString("caseId", caseId.toString());
        stepCtx.putString(CTX_DOC_ID, docId.toString());

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);

        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        Query query1 = mock(Query.class);
        Query query2 = mock(Query.class);
        when(query1.getQueryId()).thenReturn(q1);
        when(query2.getQueryId()).thenReturn(q2);
        when(queryResolver.resolve()).thenReturn(Arrays.asList(query1, query2));

        var def1 = mock(QueryDefinitionLatest.class);
        var def2 = mock(QueryDefinitionLatest.class);
        when(def1.getQueryPrompt()).thenReturn("prompt-1");
        when(def1.getUserQuery()).thenReturn("user-query-1");
        when(def2.getQueryPrompt()).thenReturn("prompt-2");
        when(def2.getUserQuery()).thenReturn("user-query-2");
        when(qdlRepo.findByQueryId(q1)).thenReturn(Optional.of(def1));
        when(qdlRepo.findByQueryId(q2)).thenReturn(Optional.of(def2));

        when(jdbc.query(
                startsWith("SELECT version FROM answer_reservations"),
                any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Integer>>any()
        )).thenAnswer(inv -> {
            SqlParameterSource p = inv.getArgument(1);
            UUID queryId = (UUID) p.getValue("query_id");
            if (q1.equals(queryId)) return Collections.<Integer>emptyList();
            if (q2.equals(queryId)) return Collections.singletonList(7);
            return Collections.<Integer>emptyList();
        });

        when(jdbc.queryForObject(
                startsWith("SELECT get_or_create_answer_version"),
                any(SqlParameterSource.class),
                eq(Integer.class)
        )).thenReturn(3);

        when(jdbc.update(
                startsWith("UPDATE answer_reservations SET status='IN_PROGRESS'"),
                any(SqlParameterSource.class)
        )).thenReturn(1);

        // Upstream responses
        var resp1 = new UserQueryAnswerReturnedSuccessfully()
                .userQuery("user-query-1")
                .queryPrompt("prompt-1")
                .llmResponse("ans-" + q1)
                .chunkedEntries(List.of(Map.of("id", 1)));
        var resp2 = new UserQueryAnswerReturnedSuccessfully()
                .userQuery("user-query-2")
                .queryPrompt("prompt-2")
                .llmResponse("ans-" + q2)
                .chunkedEntries(List.of(Map.of("id", 2)));

        // Mock OpenAPI call – verify we get correct userQuery/prompt in the request
        when(documentInformationSummarisedApi.answerUserQuery(argThat(req ->
                req instanceof AnswerUserQueryRequest r &&
                        "user-query-1".equals(r.getUserQuery()) &&
                        "prompt-1".equals(r.getQueryPrompt())
        ))).thenReturn(ResponseEntity.ok(resp1));

        when(documentInformationSummarisedApi.answerUserQuery(argThat(req ->
                req instanceof AnswerUserQueryRequest r &&
                        "user-query-2".equals(r.getUserQuery()) &&
                        "prompt-2".equals(r.getQueryPrompt())
        ))).thenReturn(ResponseEntity.ok(resp2));

        // Capture batches
        ArgumentCaptor<MapSqlParameterSource[]> answersBatchCaptor = ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        when(jdbc.batchUpdate(startsWith("INSERT INTO answers"), answersBatchCaptor.capture()))
                .thenReturn(new int[]{1,1});

        ArgumentCaptor<MapSqlParameterSource[]> doneBatchCaptor = ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        when(jdbc.batchUpdate(startsWith("UPDATE answer_reservations SET status='DONE'"), doneBatchCaptor.capture()))
                .thenReturn(new int[]{1,1});

        RepeatStatus status = newTasklet().execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        verify(jdbc, times(2)).query(
                startsWith("SELECT version FROM answer_reservations"),
                any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Integer>>any()
        );
        verify(jdbc, times(1)).queryForObject(
                startsWith("SELECT get_or_create_answer_version"),
                any(SqlParameterSource.class),
                eq(Integer.class)
        );
        verify(jdbc, times(2)).update(
                startsWith("UPDATE answer_reservations SET status='IN_PROGRESS'"),
                any(SqlParameterSource.class)
        );

        // Assert payloads
        MapSqlParameterSource[] answersBatch = answersBatchCaptor.getValue();
        MapSqlParameterSource[] doneBatch = doneBatchCaptor.getValue();
        assertThat(answersBatch).hasSize(2);
        assertThat(doneBatch).hasSize(2);

        Set<UUID> seenQueryIds = new HashSet<>();
        Set<String> seenAnswers = new HashSet<>();
        for (MapSqlParameterSource m : answersBatch) {
            Map<String, Object> vals = m.getValues();
            assertThat(vals.get("case_id")).isEqualTo(caseId);
            assertThat(vals.get("doc_id")).isEqualTo(docId);
            assertThat(vals.get("version")).isInstanceOf(Integer.class);

            String answer = (String) vals.get("answer");
            assertThat(answer).startsWith("ans-");
            seenAnswers.add(answer);

            String llmInput = (String) vals.get("llm_input");
            assertThat(llmInput).contains("\"queryPrompt\":\"prompt-");
            assertThat(llmInput).contains("\"userQuery\":\"user-query-");
            assertThat(llmInput).contains("\"metadataFilters\"");
            assertThat(llmInput).contains("\"provenanceChunksSample\"");

            seenQueryIds.add((UUID) vals.get("query_id"));
        }
        assertThat(seenQueryIds).containsExactlyInAnyOrder(q1, q2);
        assertThat(seenAnswers).containsExactlyInAnyOrder("ans-" + q1, "ans-" + q2);

        Set<UUID> doneQueryIds = new HashSet<>();
        for (MapSqlParameterSource m : doneBatch) {
            Map<String, Object> vals = m.getValues();
            assertThat(vals.get("case_id")).isEqualTo(caseId);
            assertThat(vals.get("doc_id")).isEqualTo(docId);
            doneQueryIds.add((UUID) vals.get("query_id"));
        }
        assertThat(doneQueryIds).containsExactlyInAnyOrder(q1, q2);
    }

    @Test
    @DisplayName("Returns FINISHED and does nothing when caseId/docId missing")
    void returnsFinishedWhenNoContext() throws Exception {
        ExecutionContext stepCtx = new ExecutionContext();
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);

        RepeatStatus status = newTasklet().execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(jdbc, queryResolver, qdlRepo, documentInformationSummarisedApi);
    }

    @Test
    @DisplayName("Returns FINISHED when no queries to run")
    void returnsFinishedWhenNoQueries() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final UUID docId  = UUID.randomUUID();
        ExecutionContext stepCtx = new ExecutionContext();
        stepCtx.putString("caseId", caseId.toString());
        stepCtx.putString(CTX_DOC_ID, docId.toString());

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        when(queryResolver.resolve()).thenReturn(Collections.emptyList());

        RepeatStatus status = newTasklet().execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(jdbc, qdlRepo, documentInformationSummarisedApi);
    }
}
