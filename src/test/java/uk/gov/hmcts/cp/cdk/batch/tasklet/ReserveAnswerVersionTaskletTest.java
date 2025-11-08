package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_UPLOAD_VERIFIED_KEY;

import uk.gov.hmcts.cp.cdk.batch.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class ReserveAnswerVersionTaskletTest {

    private QueryResolver queryResolver;
    private NamedParameterJdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private DocumentIdResolver documentIdResolver;

    private StepContribution stepContribution;
    private ChunkContext chunkContext;
    private StepExecution stepExecution;
    private JobExecution jobExecution;

    private ExecutionContext stepCtx;
    private ExecutionContext jobCtx;

    private ReserveAnswerVersionTasklet tasklet;

    @BeforeEach
    void setUp() {
        queryResolver = mock(QueryResolver.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        txManager = mock(PlatformTransactionManager.class);
        documentIdResolver = mock(DocumentIdResolver.class);

        stepContribution = mock(StepContribution.class);
        chunkContext = mock(ChunkContext.class);
        stepExecution = mock(StepExecution.class);
        jobExecution = mock(JobExecution.class);

        stepCtx = new ExecutionContext();
        jobCtx = new ExecutionContext();

        tasklet = new ReserveAnswerVersionTasklet(queryResolver, jdbc, txManager, documentIdResolver);

        lenient().when(stepContribution.getStepExecution()).thenReturn(stepExecution);
        lenient().when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        lenient().when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        lenient().when(jobExecution.getExecutionContext()).thenReturn(jobCtx);

        lenient().when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lenient().doNothing().when(txManager).commit(any());
        lenient().doNothing().when(txManager).rollback(any());
    }

    @Test
    @DisplayName("Finishes when StepExecution is null")
    void finishesWhenStepExecutionNull() throws Exception {
        when(stepContribution.getStepExecution()).thenReturn(null);

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(queryResolver, jdbc);
    }

    @Test
    @DisplayName("Skips when caseId missing")
    void skipsWhenIdsMissing() throws Exception {
        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(queryResolver, jdbc, documentIdResolver);
    }

    @Test
    @DisplayName("Skips when UUIDs invalid")
    void skipsWhenInvalidUuids() throws Exception {
        stepCtx.putString(CTX_CASE_ID_KEY, "not-a-uuid");
        stepCtx.putString(CTX_DOC_ID_KEY, "also-not-a-uuid");

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(queryResolver, jdbc);
    }

    @Test
    @DisplayName("Skips when case_documents entry does not exist")
    void skipsWhenDocMissing() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        jobCtx.put(CTX_UPLOAD_VERIFIED_KEY + ":" + docId, true);

        when(jdbc.queryForObject(
                eq("SELECT EXISTS (SELECT 1 FROM case_documents WHERE case_id=:case_id AND doc_id=:doc_id)"),
                any(MapSqlParameterSource.class),
                eq(Boolean.class)
        )).thenReturn(Boolean.FALSE);

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(jdbc).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(queryResolver);
    }

    @Test
    @DisplayName("Happy path: reserves versions for unique query ids and touches updated_at")
    void happyPathReservesVersions() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        jobCtx.put(CTX_UPLOAD_VERIFIED_KEY + ":" + docId, true);

        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE);

        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();

        Query a = mock(Query.class);
        when(a.getQueryId()).thenReturn(q1);
        Query b = mock(Query.class);
        when(b.getQueryId()).thenReturn(q2);
        Query c = mock(Query.class);
        when(c.getQueryId()).thenReturn(q1);

        when(queryResolver.resolve()).thenReturn(List.of(a, b, c));

        Map<String, Object> r1 = new HashMap<>();
        r1.put("query_id", q1);
        r1.put("version", 3);
        Map<String, Object> r2 = new HashMap<>();
        r2.put("query_id", q2);
        r2.put("version", 1);

        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(r1, r2));

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        ArgumentCaptor<MapSqlParameterSource[]> captor = ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        verify(jdbc).batchUpdate(
                startsWith("UPDATE answer_reservations"),
                captor.capture()
        );
        MapSqlParameterSource[] batch = captor.getValue();
        assertThat(batch).hasSize(2);

        Set<UUID> seen = new HashSet<>();
        for (MapSqlParameterSource s : batch) {
            UUID bid = (UUID) s.getValues().get("case_id");
            UUID qid = (UUID) s.getValues().get("query_id");
            UUID did = (UUID) s.getValues().get("doc_id");
            Integer ver = (Integer) s.getValues().get("version");

            assertThat(bid).isEqualTo(caseId);
            assertThat(did).isEqualTo(docId);
            assertThat(qid).isIn(q1, q2);
            assertThat(ver).isIn(3, 1);
            seen.add(qid);
        }
        assertThat(seen).containsExactlyInAnyOrder(q1, q2);
    }

    @Test
    @DisplayName("Overrides docId from DB and updates context before reservations")
    void overridesDocIdAndUpdatesContext() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID oldDocId = UUID.randomUUID();
        UUID resolvedDocId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, oldDocId.toString());
        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        jobCtx.put(CTX_UPLOAD_VERIFIED_KEY + ":" + resolvedDocId, true);

        when(documentIdResolver.resolveExistingDocId(eq(caseId), eq(materialId)))
                .thenReturn(Optional.of(resolvedDocId));

        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE);

        UUID q1 = UUID.randomUUID();
        Query a = mock(Query.class);
        when(a.getQueryId()).thenReturn(q1);
        when(queryResolver.resolve()).thenReturn(List.of(a));

        Map<String, Object> r1 = new HashMap<>();
        r1.put("query_id", q1);
        r1.put("version", 2);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(r1));

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        // Context updated
        assertThat(stepCtx.getString(CTX_DOC_ID_KEY)).isEqualTo(resolvedDocId.toString());

        ArgumentCaptor<MapSqlParameterSource[]> captor = ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        verify(jdbc).batchUpdate(startsWith("UPDATE answer_reservations"), captor.capture());
        MapSqlParameterSource[] batch = captor.getValue();
        assertThat(batch).hasSize(1);
        assertThat(batch[0].getValues().get("doc_id")).isEqualTo(resolvedDocId);
    }

    @Test
    @DisplayName("Skips early when verified flag missing for resolved docId")
    void skipsWhenVerifiedMissingForResolvedDocId() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID oldDocId = UUID.randomUUID();
        UUID resolvedDocId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, oldDocId.toString());
        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());

        when(documentIdResolver.resolveExistingDocId(eq(caseId), eq(materialId)))
                .thenReturn(Optional.of(resolvedDocId));

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        verifyNoInteractions(jdbc, queryResolver);
    }
}
