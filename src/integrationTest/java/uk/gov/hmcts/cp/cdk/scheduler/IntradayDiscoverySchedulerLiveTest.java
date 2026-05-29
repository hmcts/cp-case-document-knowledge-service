package uk.gov.hmcts.cp.cdk.scheduler;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.fromString;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.util.UtilConstants.USER_WITH_PERMISSIONS;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Verifies that IntradayDiscoveryScheduler acquires a ShedLock and dispatches
 * ingestion tasks. The docker-compose stack overrides the cron to fire every 30
 * seconds so tests complete in well under a minute.
 */
class IntradayDiscoverySchedulerLiveTest extends AbstractHttpLiveTest {

    private static final String LOCK_NAME = "intradayDiscoveryScheduler";
    private static final String HEARINGS_PATH = "/hearing-query-api/query/api/rest/hearing/hearings";
    private static final String COURT_CENTRE_ID = "courtCentreId";

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

    @Test
    void scheduler_shouldCallHearingApi_forEachTodaysScheduledRequest() throws SQLException {
        configureFor("localhost", 8089);

        final UUID courtCentreId1 = randomUUID();
        final UUID courtCentreId2 = randomUUID();
        final UUID id1 = randomUUID();
        final UUID id2 = randomUUID();
        final UUID cppuid = fromString(USER_WITH_PERMISSIONS);
        final LocalDate today = LocalDate.now();

        insertScheduledIngestionRequest(id1, cppuid, courtCentreId1, randomUUID(), today);
        insertScheduledIngestionRequest(id2, cppuid, courtCentreId2, randomUUID(), today);

        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofSeconds(5))
                    .until(() ->
                        !findAll(getRequestedFor(urlPathEqualTo(HEARINGS_PATH))
                                .withQueryParam(COURT_CENTRE_ID, equalTo(courtCentreId1.toString()))).isEmpty()
                        &&
                        !findAll(getRequestedFor(urlPathEqualTo(HEARINGS_PATH))
                                .withQueryParam(COURT_CENTRE_ID, equalTo(courtCentreId2.toString()))).isEmpty()
                    );

            assertThat(findAll(getRequestedFor(urlPathEqualTo(HEARINGS_PATH))
                    .withQueryParam(COURT_CENTRE_ID, equalTo(courtCentreId1.toString()))))
                    .as("hearing API must be called for courtCentreId1 seeded row")
                    .isNotEmpty();
            assertThat(findAll(getRequestedFor(urlPathEqualTo(HEARINGS_PATH))
                    .withQueryParam(COURT_CENTRE_ID, equalTo(courtCentreId2.toString()))))
                    .as("hearing API must be called for courtCentreId2 seeded row")
                    .isNotEmpty();
        } finally {
            deleteScheduledIngestionRequests(id1, id2);
        }
    }

    private void insertScheduledIngestionRequest(final UUID id, final UUID cppuid,
                                                 final UUID courtCentreId, final UUID courtRoomId,
                                                 final LocalDate hearingDate) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scheduled_ingestion_request "
                     + "(id, cppuid, court_centre_id, court_room_id, hearing_date, created_at, updated_at) "
                     + "VALUES (?, ?, ?, ?, ?, NOW(), NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, cppuid);
            ps.setObject(3, courtCentreId);
            ps.setObject(4, courtRoomId);
            ps.setObject(5, hearingDate);
            ps.executeUpdate();
        }
    }

    private void deleteScheduledIngestionRequests(final UUID id1, final UUID id2) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM scheduled_ingestion_request WHERE id = ? OR id = ?")) {
            ps.setObject(1, id1);
            ps.setObject(2, id2);
            ps.executeUpdate();
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
