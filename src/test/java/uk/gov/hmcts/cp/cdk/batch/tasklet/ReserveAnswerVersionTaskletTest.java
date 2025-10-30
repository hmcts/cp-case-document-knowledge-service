package uk.gov.hmcts.cp.cdk.batch.tasklet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gov.hmcts.cp.cdk.batch.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;

@DisplayName("ReserveAnswerVersionTasklet tests")
@ExtendWith(MockitoExtension.class)
class ReserveAnswerVersionTaskletTest {

    @Mock private QueryResolver queryResolver;
    @Mock private NamedParameterJdbcTemplate jdbc;

    private final PlatformTransactionManager txManager = new NoopTxManager();

    @Mock private StepContribution contribution;
    @Mock private ChunkContext chunkContext;
    @Mock private StepExecution stepExecution;

    static class NoopTxManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }

    private ReserveAnswerVersionTasklet newTasklet() {
        return new ReserveAnswerVersionTasklet(queryResolver, jdbc, txManager);
    }

    @Test
    @DisplayName("Reserves versions for multiple queries in one SQL and batch-updates reservations")
    void reservesVersionsInBatch() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final UUID docId  = UUID.randomUUID();
        ExecutionContext stepCtx = new ExecutionContext();
        stepCtx.putString("caseId", caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);

        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        Query query1 = mock(Query.class);
        Query query2 = mock(Query.class);
        Query query2Dup = mock(Query.class);
        when(query1.getQueryId()).thenReturn(q1);
        when(query2.getQueryId()).thenReturn(q2);
        when(query2Dup.getQueryId()).thenReturn(q2);
        when(queryResolver.resolve()).thenReturn(Arrays.asList(query1, query2, query2Dup));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new HashMap<String, Object>() {{
            put("query_id", q1);
            put("version", 3);
        }});
        rows.add(new HashMap<String, Object>() {{
            put("query_id", q2);
            put("version", 7);
        }});

        when(jdbc.queryForList(
                startsWith("WITH q(id) AS (VALUES"),
                any(SqlParameterSource.class))
        ).thenReturn(rows);

        ArgumentCaptor<MapSqlParameterSource[]> batchCaptor = ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        when(jdbc.batchUpdate(startsWith("UPDATE answer_reservations SET updated_at = NOW()"), batchCaptor.capture()))
                .thenReturn(new int[]{1,1});

        RepeatStatus result = newTasklet().execute(contribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);

        verify(jdbc, times(1)).queryForList(startsWith("WITH q(id) AS (VALUES"), any(SqlParameterSource.class));
        verify(jdbc, times(1)).batchUpdate(startsWith("UPDATE answer_reservations SET updated_at = NOW()"), any(MapSqlParameterSource[].class));

        MapSqlParameterSource[] batch = batchCaptor.getValue();
        assertThat(batch).hasSize(2);

        Set<UUID> seenQueryIds = new HashSet<>();
        for (MapSqlParameterSource m : batch) {
            Map<String, Object> vals = m.getValues();
            assertThat(vals.get("case_id")).isEqualTo(caseId);
            assertThat(vals.get("doc_id")).isEqualTo(docId);
            assertThat(vals.get("version")).isInstanceOf(Integer.class);
            seenQueryIds.add((UUID) vals.get("query_id"));
        }
        assertThat(seenQueryIds).containsExactlyInAnyOrder(q1, q2);
    }

    @Test
    @DisplayName("Does nothing when caseId/docId missing")
    void noContext() throws Exception {
        ExecutionContext stepCtx = new ExecutionContext();
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);

        RepeatStatus result = newTasklet().execute(contribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(jdbc, queryResolver);
    }

    @Test
    @DisplayName("Does nothing when no queries")
    void noQueries() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final UUID docId  = UUID.randomUUID();
        ExecutionContext stepCtx = new ExecutionContext();
        stepCtx.putString("caseId", caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        when(queryResolver.resolve()).thenReturn(Collections.emptyList());

        RepeatStatus result = newTasklet().execute(contribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(jdbc);
    }
}
