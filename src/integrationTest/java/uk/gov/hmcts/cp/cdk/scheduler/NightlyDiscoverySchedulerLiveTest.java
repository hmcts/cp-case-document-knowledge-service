package uk.gov.hmcts.cp.cdk.scheduler;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.stub.HearingQueryApiStub.stubGetHearingCasesForDayReturnsEmptyHearingCases;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.Date;
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
 * Verifies that NightlyDiscoveryScheduler acquires a ShedLock, calculates the
 * upcoming hearing-date window, and calls the hearing-cases-for-day API for each
 * calculated date so retrieved cases can be matched against active court-centre/room
 * configurations. The docker-compose stack overrides the cron to fire every 30
 * seconds so tests complete well under a minute.
 */
class NightlyDiscoverySchedulerLiveTest extends AbstractHttpLiveTest {

    private static final String LOCK_NAME = "nightlyDiscoveryScheduler";
    private static final String HEARING_CASES_FOR_DAY_PATH = "/hearing-query-api/query/api/rest/hearing/hearing-cases-for-day";

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
    void scheduler_shouldCallHearingCasesForDayApi_forCalculatedHearingDates() throws SQLException {
        configureFor("localhost", 8089);

        final UUID courtCentreId = randomUUID();
        final UUID roomId = randomUUID();
        final UUID configurationId = randomUUID();

        // Nightly discovery retrieves hearing cases for every calculated hearing date
        // and matches them against active court-centre/room configurations; seeding
        // one active configuration is enough to exercise the whitelisting flow.
        insertDiscoverySchedulerConfiguration(configurationId, courtCentreId, roomId);
        stubGetHearingCasesForDayReturnsEmptyHearingCases();

        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofSeconds(5))
                    .until(() -> !findAll(getRequestedFor(urlPathEqualTo(HEARING_CASES_FOR_DAY_PATH))).isEmpty());

            assertThat(findAll(getRequestedFor(urlPathEqualTo(HEARING_CASES_FOR_DAY_PATH))))
                    .as("hearing-cases-for-day API must be called by nightly discovery for the calculated hearing dates")
                    .isNotEmpty();
        } finally {
            deleteDiscoverySchedulerConfiguration(configurationId);
        }
    }

    private void insertDiscoverySchedulerConfiguration(final UUID id, final UUID courtCentreId,
                                                        final UUID courtRoomId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO discovery_scheduler_configuration "
                     + "(id, court_centre_id, court_room_id, uploaded_date, version, is_active, created_at, updated_at) "
                     + "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, courtCentreId);
            ps.setObject(3, courtRoomId);
            ps.setDate(4, Date.valueOf(LocalDate.now()));
            ps.setInt(5, 1);
            ps.setBoolean(6, true);
            ps.executeUpdate();
        }
    }

    private void deleteDiscoverySchedulerConfiguration(final UUID id) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM discovery_scheduler_configuration WHERE id = ?")) {
            ps.setObject(1, id);
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
