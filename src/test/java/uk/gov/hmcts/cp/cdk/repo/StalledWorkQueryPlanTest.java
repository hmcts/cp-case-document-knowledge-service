package uk.gov.hmcts.cp.cdk.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DD-43185 (FR-005, AC-006/AC-007, OQ-018): proves the two stuck-work aggregate queries use the
 * indexes added by {@code V1014} at a documented synthetic volume (~100k rows). This is index
 * *applicability* evidence at CI-hardware scale, not production-scale EXPLAIN evidence — that is a
 * DBA follow-up outside this repository (OQ-009, ADR-003's pre-merge gate note in
 * {@code 03-stories.md}).
 *
 * <p>Runs {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} against the exact SQL strings the
 * repositories use ({@link CaseDocumentRepository#COUNT_STALLED_BY_PHASE_SQL},
 * {@link CaseQueryStatusRepository#COUNT_AWAITING_ANSWER_OLDER_THAN_SQL}) so this test cannot
 * silently drift from what production actually executes (OQ-018). JSON format is parsed properly
 * via Jackson rather than string-sliced, so a shifted plan shape fails an assertion with a readable
 * message instead of throwing {@code StringIndexOutOfBoundsException}.
 */
@DataJpaTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StalledWorkQueryPlanTest {

    private static final int SYNTHETIC_ROW_COUNT = 100_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    private NamedParameterJdbcTemplate namedJdbc;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @BeforeAll
    void seedSyntheticVolumeAndAnalyze() {
        namedJdbc = new NamedParameterJdbcTemplate(jdbc);

        // ~90% terminal/non-monitored phases (typical steady-state shape). Of the remaining 10%
        // in the four monitored phases, ~97% are fresh (within the last 10 minutes — a document
        // that just transitioned phase, not yet stalled) and ~3% are a genuinely stuck tail
        // (1-48h old). This mirrors real traffic: "stalled" is the exception, not the rule, so the
        // ingestion_phase_at filter is highly selective — unlike a uniform spread, which would
        // make almost every monitored-phase row count as "stalled" and defeat the point of
        // indexing on the timestamp at all. All values synthetic.
        jdbc.execute("""
                INSERT INTO case_documents
                    (doc_id, case_id, material_id, source, doc_name, blob_uri, content_type,
                     size_bytes, sha256_hex, uploaded_at, ingestion_phase, ingestion_phase_at,
                     defendant_id, courtdoc_id, created_at)
                SELECT
                    gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'IDPC', '',
                    'http://blob_uri', '', 0, NULL, now(),
                    (CASE WHEN random() < 0.9 THEN 'INGESTED'
                          ELSE (ARRAY['WAITING_FOR_UPLOAD','UPLOADING','UPLOADED','INGESTING'])[floor(random() * 4 + 1)]
                     END)::document_ingestion_phase_enum,
                    now() - (CASE WHEN random() < 0.97
                                  THEN random() * interval '10 minutes'
                                  ELSE interval '1 hour' + random() * interval '47 hours'
                              END),
                    NULL, NULL, now()
                FROM generate_series(1, %d)
                """.formatted(SYNTHETIC_ROW_COUNT));

        jdbc.update("INSERT INTO queries (query_id, label) VALUES (gen_random_uuid(), 'plan-test-query')");
        final UUID queryId = jdbc.queryForObject(
                "SELECT query_id FROM queries WHERE label = 'plan-test-query'", UUID.class);

        // ~90% answered (not monitored). Of the remaining 10% awaiting answer, ~97% are fresh
        // (within the last 10 minutes) and ~3% are a genuinely stuck tail (1-48h old) — same
        // realistic-selectivity reasoning as the case_documents seed above.
        jdbc.execute("""
                INSERT INTO case_query_status (case_id, query_id, status, status_at)
                SELECT
                    gen_random_uuid(), '%s',
                    (CASE WHEN random() < 0.9 THEN 'ANSWER_AVAILABLE' ELSE 'ANSWER_NOT_AVAILABLE' END)::query_lifecycle_status_enum,
                    now() - (CASE WHEN random() < 0.97
                                  THEN random() * interval '10 minutes'
                                  ELSE interval '1 hour' + random() * interval '47 hours'
                              END)
                FROM generate_series(1, %d)
                """.formatted(queryId, SYNTHETIC_ROW_COUNT));

        jdbc.execute("ANALYZE case_documents");
        jdbc.execute("ANALYZE case_query_status");
    }

    @Test
    @DisplayName("V1014 creates idx_cd_phase_phase_at and idx_cqs_awaiting_answer_at with the "
            + "expected columns and partial predicate (Scenario 3.1)")
    void shouldHaveCreatedBothMonitoringIndexes_afterFlywayMigration() {
        final String documentsIndexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_cd_phase_phase_at'", String.class);
        assertThat(documentsIndexDef)
                .as("idx_cd_phase_phase_at must be a composite over case_documents(ingestion_phase, "
                        + "ingestion_phase_at)")
                .contains("case_documents")
                .contains("ingestion_phase")
                .contains("ingestion_phase_at");

        final String queriesIndexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_cqs_awaiting_answer_at'", String.class);
        assertThat(queriesIndexDef)
                .as("idx_cqs_awaiting_answer_at must be a partial index on case_query_status(status_at) "
                        + "WHERE status = 'ANSWER_NOT_AVAILABLE'")
                .contains("case_query_status")
                .contains("status_at")
                .contains("WHERE")
                .contains("ANSWER_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("countStalledByPhase uses idx_cd_phase_phase_at, no Seq Scan on case_documents")
    void countStalledByPhase_usesCompositeIndex_notSeqScan() throws Exception {
        final JsonNode plan = explainJson(
                CaseDocumentRepository.COUNT_STALLED_BY_PHASE_SQL,
                Map.of("cutoff", OffsetDateTime.now().minusMinutes(30)));

        assertThat(planUsesIndex(plan, "idx_cd_phase_phase_at")).isTrue();
        assertThat(planHasSeqScanOn(plan, "case_documents")).isFalse();
    }

    @Test
    @DisplayName("countAwaitingAnswerOlderThan uses idx_cqs_awaiting_answer_at, no Seq Scan on case_query_status")
    void countAwaitingAnswerOlderThan_usesPartialIndex_notSeqScan() throws Exception {
        final JsonNode plan = explainJson(
                CaseQueryStatusRepository.COUNT_AWAITING_ANSWER_OLDER_THAN_SQL,
                Map.of("cutoff", OffsetDateTime.now().minusMinutes(30)));

        assertThat(planUsesIndex(plan, "idx_cqs_awaiting_answer_at")).isTrue();
        assertThat(planHasSeqScanOn(plan, "case_query_status")).isFalse();
    }

    @Test
    @DisplayName("A bound-parameter status silently disables the partial index — Scenario 3.7, "
            + "the regression this test exists for (ADR-003's literal-status coupling enforced by "
            + "test, not comment). Robustness caveat: this asserts planner behaviour, the most "
            + "brittle assertion in this suite — a PostgreSQL minor-version change could alter it.")
    void shouldLosePartialIndex_whenStatusIsBoundInsteadOfLiteral() throws Exception {
        final OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);

        final JsonNode literalPlan = explainJson(
                CaseQueryStatusRepository.COUNT_AWAITING_ANSWER_OLDER_THAN_SQL,
                Map.of("cutoff", cutoff));
        assertThat(planUsesIndex(literalPlan, "idx_cqs_awaiting_answer_at"))
                .as("the literal-status form, as production spells it, must use the partial index")
                .isTrue();

        // Deliberately NOT the production query — an ad-hoc bound-parameter variant, constructed
        // only in this test, to prove why the literal matters. Production's own method continues
        // to use the literal form above; this does not change production code.
        final String boundStatusSql = """
                SELECT COUNT(*)
                  FROM case_query_status cqs
                 WHERE cqs.status = CAST(:status AS query_lifecycle_status_enum)
                   AND cqs.status_at < :cutoff
                """;
        final JsonNode boundPlan = explainJson(boundStatusSql, Map.of(
                "status", "ANSWER_NOT_AVAILABLE",
                "cutoff", cutoff));
        assertThat(planUsesIndex(boundPlan, "idx_cqs_awaiting_answer_at"))
                .as("PostgreSQL cannot prove the predicate implication for a bound parameter, so the "
                        + "partial index must NOT be available to this form — the single most likely "
                        + "silent performance regression in this ticket")
                .isFalse();
    }

    @Test
    @DisplayName("Both aggregates complete comfortably under a loose CI-hardware smoke bound — "
            + "synthetic volume, NOT production-scale evidence (see class Javadoc / AC-012's "
            + "re-scoping note, OQ-009)")
    void aggregates_completeUnderLooseSmokeBound() throws Exception {
        final JsonNode documentsPlan = explainJson(
                CaseDocumentRepository.COUNT_STALLED_BY_PHASE_SQL,
                Map.of("cutoff", OffsetDateTime.now().minusMinutes(30)));
        final JsonNode queriesPlan = explainJson(
                CaseQueryStatusRepository.COUNT_AWAITING_ANSWER_OLDER_THAN_SQL,
                Map.of("cutoff", OffsetDateTime.now().minusMinutes(30)));

        assertThat(actualTotalTimeMs(documentsPlan))
                .as("CI-hardware smoke check only — synthetic volume, not production scale (OQ-009)")
                .isLessThan(500.0);
        assertThat(actualTotalTimeMs(queriesPlan))
                .as("CI-hardware smoke check only — synthetic volume, not production scale (OQ-009)")
                .isLessThan(500.0);
    }

    private JsonNode explainJson(final String sql, final Map<String, Object> params) throws Exception {
        final String json = namedJdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql,
                new MapSqlParameterSource(params), String.class);
        final JsonNode root = OBJECT_MAPPER.readTree(json);
        return root.get(0).get("Plan");
    }

    private boolean planUsesIndex(final JsonNode plan, final String indexName) {
        if (indexName.equals(textOrNull(plan, "Index Name"))) {
            return true;
        }
        for (final JsonNode child : plan.path("Plans")) {
            if (planUsesIndex(child, indexName)) {
                return true;
            }
        }
        return false;
    }

    private boolean planHasSeqScanOn(final JsonNode plan, final String relationName) {
        if ("Seq Scan".equals(textOrNull(plan, "Node Type"))
                && relationName.equals(textOrNull(plan, "Relation Name"))) {
            return true;
        }
        for (final JsonNode child : plan.path("Plans")) {
            if (planHasSeqScanOn(child, relationName)) {
                return true;
            }
        }
        return false;
    }

    private double actualTotalTimeMs(final JsonNode plan) {
        return plan.get("Actual Total Time").asDouble();
    }

    private static String textOrNull(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        return value == null ? null : value.asText();
    }
}
