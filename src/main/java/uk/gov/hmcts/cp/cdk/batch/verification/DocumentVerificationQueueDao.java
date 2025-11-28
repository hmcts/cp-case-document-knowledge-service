package uk.gov.hmcts.cp.cdk.batch.verification;

import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus;
import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationTask;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Low-level queue operations using SKIP LOCKED so that multiple instances
 * can safely share the verification workload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVerificationQueueDao {

    private static final String CLAIM_SQL = """
        UPDATE document_verification_task
           SET lock_owner       = :owner,
               lock_acquired_at = NOW(),
               status           = 'IN_PROGRESS'
         WHERE id IN (
             SELECT id
               FROM document_verification_task
              WHERE status IN ('PENDING', 'IN_PROGRESS')
                AND next_attempt_at <= NOW()
              ORDER BY next_attempt_at
              FOR UPDATE SKIP LOCKED
              LIMIT :limit
         )
         RETURNING id,
                   doc_id,
                   case_id,
                   blob_name,
                   attempt_count,
                   max_attempts,
                   status,
                   last_status,
                   last_reason,
                   last_status_ts,
                   next_attempt_at,
                   lock_owner,
                   lock_acquired_at,
                   created_at,
                   updated_at
        """;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    /**
     * Claims up to {@code limit} tasks for the given owner.
     *
     * @param owner lock owner identifier (per-instance id)
     * @param limit maximum number of rows to claim in a single call
     * @return list of claimed tasks (may be empty)
     */
    public List<DocumentVerificationTask> claimBatch(final String owner, final int limit) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("limit", Integer.valueOf(limit));

        return this.namedParameterJdbcTemplate.query(CLAIM_SQL, parameters, new TaskRowMapper());
    }

    /**
     * RowMapper to rebuild DocumentVerificationTask from RETURNING clause.
     */
    private static final class TaskRowMapper implements RowMapper<DocumentVerificationTask> {

        @Override
        public DocumentVerificationTask mapRow(final ResultSet resultSet, final int rowNum) throws SQLException {
            final DocumentVerificationTask task = new DocumentVerificationTask();

            task.setId(Long.valueOf(resultSet.getLong("id")));
            task.setDocId((UUID) resultSet.getObject("doc_id"));
            task.setCaseId((UUID) resultSet.getObject("case_id"));
            task.setBlobName(resultSet.getString("blob_name"));
            task.setAttemptCount(resultSet.getInt("attempt_count"));
            task.setMaxAttempts(resultSet.getInt("max_attempts"));

            final String statusValue = resultSet.getString("status");
            if (statusValue != null) {
                task.setStatus(DocumentVerificationStatus.valueOf(statusValue));
            }

            task.setLastStatus(resultSet.getString("last_status"));
            task.setLastReason(resultSet.getString("last_reason"));

            task.setLastStatusTimestamp(getOffsetDateTime(resultSet, "last_status_ts"));
            task.setNextAttemptAt(getOffsetDateTime(resultSet, "next_attempt_at"));
            task.setLockOwner(resultSet.getString("lock_owner"));
            task.setLockAcquiredAt(getOffsetDateTime(resultSet, "lock_acquired_at"));
            task.setCreatedAt(getOffsetDateTime(resultSet, "created_at"));
            task.setUpdatedAt(getOffsetDateTime(resultSet, "updated_at"));

            return task;
        }

        private static OffsetDateTime getOffsetDateTime(final ResultSet resultSet,
                                                        final String columnLabel) throws SQLException {
            OffsetDateTime result = null;
            if (columnLabel != null) {
                result = resultSet.getObject(columnLabel, OffsetDateTime.class);
            }
            return result;
        }
    }
}
