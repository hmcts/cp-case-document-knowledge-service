package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_UPLOAD_VERIFIED_KEY;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import uk.gov.hmcts.cp.cdk.batch.support.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;

@ExtendWith(MockitoExtension.class)
class ReserveAnswerVersionTaskletTest {

    private static final String SQL_EXISTS_CASE_DOC =
            "SELECT EXISTS (SELECT 1 FROM case_documents WHERE case_id=:case_id AND doc_id=:doc_id)";
    private static final String SQL_EXISTING_RESERVATIONS =
            "SELECT query_id FROM answer_reservations WHERE case_id=:case_id AND doc_id=:doc_id";

    @Mock
    private QueryResolver queryResolver;

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private StepContribution contribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private StepExecution stepExecution;

    @Mock
    private JobExecution jobExecution;

    private PlatformTransactionManager txManager;
    private ReserveAnswerVersionTasklet tasklet;

    private ExecutionContext stepCtx;
    private ExecutionContext jobCtx;

    @BeforeEach
    void setUp() {
        // Minimal no-op tx manager so TransactionTemplate can run without a real DB tx
        txManager = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(final TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(final TransactionStatus status) { }
            @Override public void rollback(final TransactionStatus status) { }
        };

        tasklet = new ReserveAnswerVersionTasklet(queryResolver, jdbc, txManager);

        stepCtx = new ExecutionContext();
        jobCtx = new ExecutionContext();

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        when(jobExecution.getExecutionContext()).thenReturn(jobCtx);
    }

    @Test
    @DisplayName("Missing caseId/docId → FINISHED with no work")
    void missingIdsSkips() {
        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("Not verified → FINISHED and no DB calls")
    void notVerifiedSkips() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());

        // No verified flag in job context
        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("case_documents missing → FINISHED; no reservation SQL executed")
    void caseDocumentMissingSkips() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_ID_KEY, UUID.randomUUID().toString());

        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
        jobCtx.put(verifiedKey, true);

        when(jdbc.queryForObject(eq(SQL_EXISTS_CASE_DOC), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.FALSE);

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        // Only exists check should be called; no reservations scan
        verify(jdbc, never()).query(eq(SQL_EXISTING_RESERVATIONS), any(MapSqlParameterSource.class), anyUuidRowMapper());
    }

    @Test
    @DisplayName("No queries resolved → FINISHED; no reservations scan executed")
    void noQueriesResolvedSkips() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());

        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
        jobCtx.put(verifiedKey, true);

        when(jdbc.queryForObject(eq(SQL_EXISTS_CASE_DOC), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE);
        when(queryResolver.resolve()).thenReturn(List.of());

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(jdbc, never()).query(eq(SQL_EXISTING_RESERVATIONS), any(MapSqlParameterSource.class), anyUuidRowMapper());
    }

    @Test
    @DisplayName("All queries already reserved → no-op (no get_or_create invoked)")
    void alreadyReservedNoop() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        final UUID q1 = UUID.randomUUID();
        final UUID q2 = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());

        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
        jobCtx.put(verifiedKey, true);

        // case exists
        when(jdbc.queryForObject(eq(SQL_EXISTS_CASE_DOC), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE);

        // resolver returns two queries (plus a null to prove filtering)
        final Query qq1 = new Query(); qq1.setQueryId(q1); qq1.setLabel("Q1");
        final Query qq2 = new Query(); qq2.setQueryId(q2); qq2.setLabel("Q2");
        final Query qqNull = new Query(); // null id
        when(queryResolver.resolve()).thenReturn(List.of(qq1, qq2, qqNull));

        // DB already has both reservations
        when(jdbc.query(eq(SQL_EXISTING_RESERVATIONS), any(MapSqlParameterSource.class), anyUuidRowMapper()))
                .thenReturn(List.of(q1, q2));

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        // Ensure we did not hit the VALUES/CTE call for get_or_create…
        verify(jdbc, never()).queryForList(
                argThat(sql -> sql != null && sql.contains("get_or_create_answer_version")),
                any(MapSqlParameterSource.class)
        );
    }

    @Test
    @DisplayName("Reserves only missing queries (idempotent)")
    void reservesOnlyMissing() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        final UUID q1 = UUID.randomUUID();
        final UUID q2 = UUID.randomUUID();

        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_ID_KEY, UUID.randomUUID().toString());

        final String verifiedKey = CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
        jobCtx.put(verifiedKey, true);

        when(jdbc.queryForObject(eq(SQL_EXISTS_CASE_DOC), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE);

        final Query qq1 = new Query(); qq1.setQueryId(q1); qq1.setLabel("Q1");
        final Query qq2 = new Query(); qq2.setQueryId(q2); qq2.setLabel("Q2");
        when(queryResolver.resolve()).thenReturn(List.of(qq1, qq2));

        // Only q1 already exists; q2 should be reserved
        when(jdbc.query(eq(SQL_EXISTING_RESERVATIONS), any(MapSqlParameterSource.class), anyUuidRowMapper()))
                .thenReturn(List.of(q1));

        // Capture the reservation CTE call
        final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        when(jdbc.queryForList(sqlCaptor.capture(), paramsCaptor.capture()))
                .thenReturn(Collections.emptyList());

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        // Assert we invoked the CTE/get_or_create call
        final String usedSql = sqlCaptor.getValue();
        assertThat(usedSql).contains("WITH q(id) AS (VALUES").contains("get_or_create_answer_version");

        // Assert parameters include case_id, doc_id and exactly one q* param with the missing query (q2)
        final Map<String, Object> values = paramsCaptor.getValue().getValues();
        assertThat(values.get("case_id")).isEqualTo(caseId);
        assertThat(values.get("doc_id")).isEqualTo(docId);

        // Find q* keys (there should be exactly 1: q0)
        final long qParams = values.keySet().stream().filter(k -> k.startsWith("q")).count();
        assertThat(qParams).isEqualTo(1);
        assertThat(values.values()).contains(q2);
    }

    // ---- typed matcher helpers to satisfy -Werror on generics ----

    private static RowMapper<UUID> anyUuidRowMapper() {
        return org.mockito.ArgumentMatchers.<RowMapper<UUID>>any();
    }
}
