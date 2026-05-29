package uk.gov.hmcts.cp.cdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Verifies that IntradayDiscoveryScheduler acquires a ShedLock and writes a row
 * to the shedlock table. The docker-compose stack overrides the cron to fire
 * every 30 seconds so the test completes in well under a minute.
 */
class IntradayDiscoverySchedulerLiveTest extends AbstractHttpLiveTest {

    private static final String LOCK_NAME = "intradayDiscoveryScheduler";

    @Test
    void scheduler_shouldAcquireShedLock_andPopulateShedlockTable() throws SQLException {
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> {
                    try (Connection conn = openConnection()) {
                        return queryShedlockRow(conn, LOCK_NAME) != null;
                    }
                });

        try (Connection conn = openConnection()) {
            final ShedlockRow row = queryShedlockRow(conn, LOCK_NAME);
            assertThat(row).as("shedlock row for '%s' must exist", LOCK_NAME).isNotNull();
            assertThat(row.lockedAt()).as("locked_at must be set").isNotNull();
            assertThat(row.lockUntil()).as("lock_until must be set").isNotNull();
            assertThat(row.lockedBy()).as("locked_by must not be blank").isNotBlank();
        }
    }

    @Test
    void scheduler_lockUntil_shouldBeLaterThanLockedAt() throws SQLException {
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> {
                    try (Connection conn = openConnection()) {
                        return queryShedlockRow(conn, LOCK_NAME) != null;
                    }
                });

        try (Connection conn = openConnection()) {
            final ShedlockRow row = queryShedlockRow(conn, LOCK_NAME);
            assertThat(row).isNotNull();
            assertThat(row.lockUntil())
                    .as("lock_until must be after locked_at")
                    .isAfter(row.lockedAt());
        }
    }

    private ShedlockRow queryShedlockRow(final Connection conn, final String lockName) throws SQLException {
        ShedlockRow result = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, lock_until, locked_at, locked_by FROM shedlock WHERE name = ?")) {
            ps.setString(1, lockName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result = new ShedlockRow(
                            rs.getString("name"),
                            rs.getTimestamp("lock_until"),
                            rs.getTimestamp("locked_at"),
                            rs.getString("locked_by")
                    );
                }
            }
        }
        return result;
    }

    private record ShedlockRow(String name, Timestamp lockUntil, Timestamp lockedAt, String lockedBy) {}
}
