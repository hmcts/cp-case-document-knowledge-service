package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_QUERY_LEVEL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_RAG_TRANSACTION_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that CheckStatusOfAnswerGenerationTask persists the RAG transactionId it already
 * resolves for status-polling into the answer row it writes (DD-43084).
 *
 * <p>GENERATE_ANSWER_FOR_QUERY / CHECK_STATUS_OF_ANSWER_GENERATION are only ever dispatched
 * internally, deep in the ingestion pipeline — there is no HTTP entry point that reaches them
 * directly. Task Manager's jobs table (task-manager-service, polled every
 * {@code job.executor.poll-interval}, default 5s) is the seam this test drives instead: seed a
 * job row addressed to CHECK_STATUS_OF_ANSWER_GENERATION with the same job-data shape
 * GenerateAnswerForQueryTask would have produced, let the live app's scheduled JobExecutor pick
 * it up and run the real task against the real DB and the existing RAG WireMock stub
 * ({@code /answer-user-query-async-status/.*} → ANSWER_GENERATED), then assert the persisted
 * column via JDBC.
 */
class CheckStatusOfAnswerGenerationRagTransactionIdLiveTest extends AbstractHttpLiveTest {

    private static final String INSERT_JOB_SQL =
            "INSERT INTO jobs (job_id, assigned_task_name, assigned_task_start_time, job_data, "
                    + "priority, retry_attempts_remaining, worker_id, worker_lock_time) "
                    + "VALUES (?, ?, NOW(), ?, 10, 3, NULL, NULL)";

    @Test
    @DisplayName("Default-level answer persists the RAG transactionId into answers.rag_transaction_id")
    void checkStatusTask_persistsRagTransactionId_forDefaultLevelAnswer() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final UUID queryId = UUID.randomUUID();
        final UUID transactionId = UUID.randomUUID();

        seedQuery(queryId);
        seedCheckStatusJob(caseId, queryId, transactionId, null);

        try {
            final UUID persisted = awaitRagTransactionId("answers", caseId, queryId);
            assertThat(persisted).isEqualTo(transactionId);
        } finally {
            cleanup("answers", caseId, queryId);
        }
    }

    @Test
    @DisplayName("CASE-level answer persists the RAG transactionId into case_level_latest_doc_answers.rag_transaction_id")
    void checkStatusTask_persistsRagTransactionId_forCaseLevelAnswer() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final UUID queryId = UUID.randomUUID();
        final UUID transactionId = UUID.randomUUID();

        seedQuery(queryId);
        seedCheckStatusJob(caseId, queryId, transactionId, "CASE");

        try {
            final UUID persisted = awaitRagTransactionId("case_level_latest_doc_answers", caseId, queryId);
            assertThat(persisted).isEqualTo(transactionId);
        } finally {
            cleanup("case_level_latest_doc_answers", caseId, queryId);
        }
    }

    private void seedQuery(final UUID queryId) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO queries (query_id, label, created_at) VALUES (?, 'RAG transactionId live test query', NOW())")) {
            ps.setObject(1, queryId);
            ps.executeUpdate();
        }
    }

    private void seedCheckStatusJob(final UUID caseId, final UUID queryId, final UUID transactionId,
                                    final String queryLevelOrNull) throws SQLException {
        final String levelField = queryLevelOrNull == null
                ? ""
                : ",\"%s\":\"%s\"".formatted(CTX_QUERY_LEVEL, queryLevelOrNull);
        final String jobData = "{\"%s\":\"%s\",\"%s\":\"%s\",\"%s\":\"%s\"%s}".formatted(
                CTX_CASE_ID_KEY, caseId, CTX_SINGLE_QUERY_ID, queryId, CTX_RAG_TRANSACTION_ID, transactionId, levelField);

        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_JOB_SQL)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, CHECK_STATUS_OF_ANSWER_GENERATION);
            ps.setString(3, jobData);
            ps.executeUpdate();
        }
    }

    private UUID awaitRagTransactionId(final String table, final UUID caseId, final UUID queryId) {
        final AtomicReference<UUID> found = new AtomicReference<>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    found.set(fetchRagTransactionId(table, caseId, queryId));
                    return found.get() != null;
                });
        return found.get();
    }

    private UUID fetchRagTransactionId(final String table, final UUID caseId, final UUID queryId) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT rag_transaction_id FROM " + table + " WHERE case_id = ? AND query_id = ?")) {
            ps.setObject(1, caseId);
            ps.setObject(2, queryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("rag_transaction_id", UUID.class);
                }
                return null;
            }
        }
    }

    private void cleanup(final String table, final UUID caseId, final UUID queryId) throws SQLException {
        try (Connection c = openConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM case_query_status WHERE case_id = ? AND query_id = ?")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + table + " WHERE case_id = ? AND query_id = ?")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM queries WHERE query_id = ?")) {
                ps.setObject(1, queryId);
                ps.executeUpdate();
            }
        }
    }
}
