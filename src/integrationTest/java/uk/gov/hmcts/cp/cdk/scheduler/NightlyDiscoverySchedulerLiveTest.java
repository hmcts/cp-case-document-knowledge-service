package uk.gov.hmcts.cp.cdk.scheduler;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.stub.HearingQueryApiStub.stubGetHearingCasesForDayReturnsHearingCase;
import static uk.gov.hmcts.cp.cdk.stub.ProgressionQueryApiStub.stubGetCourtDocumentsForAllDefendantsReturnsEmpty;

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
 * upcoming hearing-date window, calls the hearing-cases-for-day API for each
 * calculated date, and — once a retrieved hearing case matches an active
 * court-centre/room configuration — dispatches a check-idpc-availability task whose
 * downstream execution calls the court-document-search API for all defendants on the
 * case. The docker-compose stack overrides the cron to fire every 30 seconds so tests
 * complete well under a minute.
 */
class NightlyDiscoverySchedulerLiveTest extends AbstractHttpLiveTest {

    private static final String LOCK_NAME = "nightlyDiscoveryScheduler";
    private static final String HEARING_CASES_FOR_DAY_PATH = "/hearing-query-api/query/api/rest/hearing/hearing-cases-for-day";
    private static final String COURT_DOCUMENT_SEARCH_PATH = "/progression-query-api/query/api/rest/progression/courtdocumentsearch";

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
        final UUID caseId = randomUUID();

        // Nightly discovery retrieves hearing cases for every calculated hearing date and
        // matches them against active court-centre/room configurations. Returning a hearing
        // case whose centre/room match the seeded configuration exercises the full flow:
        // whitelist match -> dispatchCaseDocumentIngestionTasksCheckIdpcAvailability ->
        // CheckIdpcAvailabilityAllDefendantsTask -> IdpcAvailabilityService.retrieveDocuments ->
        // ProgressionClient.getCourtDocumentsForAllDefendants. The court-document-search API is
        // stubbed to return no documents so the task completes without dispatching
        // RETRIEVE_MATERIAL_AND_UPLOAD for any defendant.
        insertDiscoverySchedulerConfiguration(configurationId, courtCentreId, roomId);
        stubGetHearingCasesForDayReturnsHearingCase(courtCentreId.toString(), roomId.toString(), caseId.toString());
        stubGetCourtDocumentsForAllDefendantsReturnsEmpty(caseId.toString());

        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofSeconds(5))
                    .until(() -> !findAll(getRequestedFor(urlPathEqualTo(HEARING_CASES_FOR_DAY_PATH))).isEmpty());

            assertThat(findAll(getRequestedFor(urlPathEqualTo(HEARING_CASES_FOR_DAY_PATH))))
                    .as("hearing-cases-for-day API must be called by nightly discovery for the calculated hearing dates")
                    .isNotEmpty();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofSeconds(5))
                    .until(() -> !findAll(getRequestedFor(urlPathEqualTo(COURT_DOCUMENT_SEARCH_PATH))
                            .withQueryParam("caseId", equalTo(caseId.toString()))).isEmpty());

            assertThat(findAll(getRequestedFor(urlPathEqualTo(COURT_DOCUMENT_SEARCH_PATH))
                    .withQueryParam("caseId", equalTo(caseId.toString()))))
                    .as("court-document-search API must be called for all defendants on the whitelisted matched hearing case caseId=%s", caseId)
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
