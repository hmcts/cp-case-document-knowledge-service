package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the stuck-work gauges and the refresh-freshness gauge (Story 4, DD-43221) are actually
 * observable on a live {@code /actuator/prometheus} scrape after a real ShedLock-guarded refresh,
 * not merely registered in-process. The docker-compose stack overrides the refresh cadence to 10s
 * so a real refresh happens well within the Awaitility window.
 *
 * <p>Extended by Story 5 (DD-43222) with the cross-cutting "all six metrics in one scrape" proof
 * and a queries-awaiting-answer seeded-row assertion — Story 4 created this class, Story 5 extends
 * it rather than duplicating a second live-test class scraping the same endpoint.
 *
 * <p>Seeded rows use a delta assertion, not an exact count — the compose Postgres is shared
 * across the whole live-test suite, so other tests' rows may already contribute to these gauges.
 */
class MonitoringMetricsHttpLiveTest extends AbstractHttpLiveTest {

    private static final String LOCK_NAME = "stalledWorkMetricsRefresh";
    private static final Pattern STALLED_WAITING_FOR_UPLOAD = Pattern.compile(
            "^cdk_documents_stalled\\{[^}]*phase=\"WAITING_FOR_UPLOAD\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern LAST_REFRESH = Pattern.compile(
            "^cdk_monitoring_last_refresh_epoch_seconds\\{[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern QUERIES_AWAITING_ANSWER = Pattern.compile(
            "^cdk_queries_awaiting_answer\\{[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);

    /**
     * The six rendered Prometheus names (ADR-001), as **hard-coded literals** — deliberately
     * NOT derived from the {@link CdkMeters} constants production code registers with. Deriving
     * them (e.g. {@code meterName.replace('.', '_')}) would make this test self-fulfilling: a
     * typo'd meter name would compute the same typo and still pass, which is exactly the
     * silent-naming trap ADR-001 exists to catch (design §Testing / Scenario 5.1). The paired
     * {@code /actuator/metrics/{id}} check below uses the {@link CdkMeters} constants for the
     * *registered* id — the two sides together are what actually proves ADR-001; neither alone
     * does.
     */
    private static final List<String> ALL_SIX_RENDERED_NAMES = List.of(
            "cdk_documents_stalled",
            "cdk_queries_awaiting_answer",
            "cdk_monitoring_last_refresh_epoch_seconds",
            "cdk_scheduler_runs_total",
            "cdk_scheduler_last_success_epoch_seconds",
            "cdk_scheduler_enabled");

    private static final List<String> ALL_SIX_MICROMETER_IDS = List.of(
            CdkMeters.DOCUMENTS_STALLED,
            CdkMeters.QUERIES_AWAITING_ANSWER,
            CdkMeters.MONITORING_LAST_REFRESH,
            CdkMeters.SCHEDULER_RUNS,
            CdkMeters.SCHEDULER_LAST_SUCCESS,
            CdkMeters.SCHEDULER_ENABLED);

    private static final Pattern DOCUMENTS_STALLED_PHASE_TAG = Pattern.compile(
            "^cdk_documents_stalled\\{[^}]*phase=\"([A-Z_]+)\"[^}]*}", Pattern.MULTILINE);

    @Test
    void stalledWorkGauges_shouldReflectASeededBackdatedRow_afterOneRefresh() throws SQLException {
        final double baseline = sampleValue(scrape(), STALLED_WAITING_FOR_UPLOAD).orElse(0.0);

        final UUID docId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        seedStalledCaseDocument(docId, caseId);

        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        final double current = sampleValue(scrape(), STALLED_WAITING_FOR_UPLOAD)
                                .orElseThrow();
                        assertThat(current).isGreaterThanOrEqualTo(baseline + 1);
                    });
        } finally {
            deleteCaseDocument(docId);
        }
    }

    @Test
    void freshnessGauge_shouldBeRecent_afterAtLeastOneRefresh() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(sampleValue(scrape(), LAST_REFRESH))
                        .hasValueSatisfying(v -> assertThat(v).isPositive()));

        final double nowEpochSeconds = System.currentTimeMillis() / 1000.0;
        final double lastRefresh = sampleValue(scrape(), LAST_REFRESH).orElseThrow();
        assertThat(lastRefresh)
                .as("freshness gauge must be recent, not stale")
                .isLessThanOrEqualTo(nowEpochSeconds + 5)
                .isGreaterThan(nowEpochSeconds - 60);
    }

    @Test
    void stalledWorkRefresh_shouldAcquireItsOwnShedLock() throws SQLException {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> {
                    try (Connection conn = openConnection()) {
                        return queryShedlockRow(conn) != null;
                    }
                });

        try (Connection conn = openConnection()) {
            final ShedlockRow row = queryShedlockRow(conn);
            assertThat(row).as("shedlock row for '%s' must exist", LOCK_NAME).isNotNull();
            assertThat(row.lockUntil())
                    .as("lock_until must be strictly after locked_at (Story 4 AC-005 / Story 5 "
                            + "AC-003; OQ-017 resolved by compose's non-zero lock-at-least-for=PT1S, "
                            + "which gives this a comfortable margin)")
                    .isAfter(row.lockedAt());
        }
    }

    @Test
    void prometheusScrape_shouldExposeAllSixMonitoringMetricsTogether() {
        // Force at least one refresh/scheduler run first so every series has actually been
        // written to, not merely pre-registered at construction.
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(sampleValue(scrape(), LAST_REFRESH)).isPresent());

        final String body = scrape();

        // Positive: all six rendered Prometheus names, as hard-coded literals (see field Javadoc).
        for (final String renderedName : ALL_SIX_RENDERED_NAMES) {
            // Every metric in this service carries the global common tags (service/cluster/region,
            // application-server-management.yml), so a real sample line always has a "{" after
            // the name — this also rules out a false-positive substring match against a
            // differently-named metric that merely starts with the same prefix.
            assertThat(body)
                    .as("expected rendered Prometheus name '%s' to appear on the scrape", renderedName)
                    .contains(renderedName + "{");
        }

        // One series per phase enum value (ADR-004's four-phase set made concrete).
        final Set<String> phaseValues = new HashSet<>();
        final Matcher phaseMatcher = DOCUMENTS_STALLED_PHASE_TAG.matcher(body);
        while (phaseMatcher.find()) {
            phaseValues.add(phaseMatcher.group(1));
        }
        assertThat(phaseValues)
                .as("cdk_documents_stalled must publish exactly one series per monitored phase")
                .containsExactlyInAnyOrder("WAITING_FOR_UPLOAD", "UPLOADING", "UPLOADED", "INGESTING");

        // Negative: ADR-001's naming finding, both directions — the counter renders WITH the
        // exposition-layer _total suffix (never bare), and never doubles it.
        assertThat(body)
                .as("the counter must never render bare, without the exposition-layer _total suffix")
                .doesNotContain("cdk_scheduler_runs{");
        assertThat(body)
                .as("the counter must never double the _total suffix")
                .doesNotContain("cdk_scheduler_runs_total_total");

        // The registered-id half: /actuator/metrics/{Micrometer id} returns 200 for every meter,
        // using the CdkMeters constant here — paired with the hard-coded literals above, a
        // divergence between what production registers and what actually renders (e.g. a
        // camelCase meter name) fails one half or the other; using constants on both sides would
        // only assert the constants equal themselves.
        for (final String micrometerId : ALL_SIX_MICROMETER_IDS) {
            final ResponseEntity<String> res = http.getForEntity(
                    baseUrl + "/actuator/metrics/" + micrometerId, String.class);
            assertThat(res.getStatusCode().is2xxSuccessful())
                    .as("GET /actuator/metrics/%s should return 200", micrometerId)
                    .isTrue();
        }
    }

    @Test
    void queriesAwaitingAnswerGauge_shouldReflectASeededBackdatedRow_afterOneRefresh() throws SQLException {
        final double baseline = sampleValue(scrape(), QUERIES_AWAITING_ANSWER).orElse(0.0);

        final UUID queryId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        seedAwaitingAnswerQuery(queryId, caseId);

        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        final double current = sampleValue(scrape(), QUERIES_AWAITING_ANSWER).orElseThrow();
                        assertThat(current).isGreaterThanOrEqualTo(baseline + 1);
                    });
        } finally {
            deleteCaseQueryStatusAndQuery(caseId, queryId);
        }
    }

    private void seedAwaitingAnswerQuery(final UUID queryId, final UUID caseId) throws SQLException {
        final OffsetDateTime stalledSince = OffsetDateTime.now().minusMinutes(61);
        try (Connection conn = openConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO queries (query_id, label, created_at) VALUES (?, ?, NOW())")) {
                ps.setObject(1, queryId);
                ps.setString(2, "monitoring-live-test-" + queryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO case_query_status (case_id, query_id, status, status_at) "
                            + "VALUES (?, ?, 'ANSWER_NOT_AVAILABLE'::query_lifecycle_status_enum, ?)")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.setObject(3, stalledSince);
                ps.executeUpdate();
            }
        }
    }

    private void deleteCaseQueryStatusAndQuery(final UUID caseId, final UUID queryId) throws SQLException {
        try (Connection conn = openConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM case_query_status WHERE case_id = ? AND query_id = ?")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM queries WHERE query_id = ?")) {
                ps.setObject(1, queryId);
                ps.executeUpdate();
            }
        }
    }

    private void seedStalledCaseDocument(final UUID docId, final UUID caseId) throws SQLException {
        final OffsetDateTime stalledSince = OffsetDateTime.now().minusMinutes(61);
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO case_documents "
                             + "(doc_id, case_id, material_id, source, doc_name, blob_uri, uploaded_at, "
                             + "ingestion_phase, ingestion_phase_at, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::document_ingestion_phase_enum, ?, ?)"
             )) {
            ps.setObject(1, docId);
            ps.setObject(2, caseId);
            ps.setObject(3, UUID.randomUUID());
            ps.setString(4, "IDPC");
            ps.setString(5, "IDPC");
            ps.setString(6, "default_blob_uri");
            ps.setObject(7, stalledSince);
            ps.setString(8, "WAITING_FOR_UPLOAD");
            ps.setObject(9, stalledSince);
            ps.setObject(10, stalledSince);
            ps.executeUpdate();
        }
    }

    private void deleteCaseDocument(final UUID docId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM case_documents WHERE doc_id = ?")) {
            ps.setObject(1, docId);
            ps.executeUpdate();
        }
    }

    private ShedlockRow queryShedlockRow(final Connection conn) throws SQLException {
        ShedlockRow result = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT lock_until, locked_at FROM shedlock WHERE name = ?")) {
            ps.setString(1, LOCK_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result = new ShedlockRow(rs.getTimestamp("lock_until"), rs.getTimestamp("locked_at"));
                }
            }
        }
        return result;
    }

    private String scrape() {
        final ResponseEntity<String> res = http.getForEntity(baseUrl + "/actuator/prometheus", String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        final String body = res.getBody();
        assertThat(body).as("prometheus scrape body must not be null").isNotNull();
        return body;
    }

    private static Optional<Double> sampleValue(final String body, final Pattern pattern) {
        final Matcher m = pattern.matcher(body);
        if (m.find()) {
            return Optional.of(Double.parseDouble(m.group(1)));
        }
        return Optional.empty();
    }

    private record ShedlockRow(Timestamp lockUntil, Timestamp lockedAt) {
    }
}
