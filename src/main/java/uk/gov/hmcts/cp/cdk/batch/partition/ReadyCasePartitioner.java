package uk.gov.hmcts.cp.cdk.batch.partition;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;

import uk.gov.hmcts.cp.cdk.batch.verification.DocumentVerificationScheduler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Partitions ready cases (documents whose ingestion_phase is INGESTED) for Answer Generation Job.
 *
 * <p>We derive readiness directly from {@code case_documents}:
 *
 * <pre>{@code
 * SELECT DISTINCT ON (cd.case_id, cd.material_id)
 *        cd.case_id,
 *        cd.doc_id,
 *        cd.material_id,
 *        cd.ingestion_phase_at AS last_updated
 *   FROM case_documents cd
 *  WHERE cd.ingestion_phase = 'INGESTED'
 *  [  AND cd.case_id IN (:case_ids) ]
 *  ORDER BY cd.case_id, cd.material_id, cd.ingestion_phase_at DESC;
 * }</pre>
 *
 * <p>This gives us, for each (case, material), the most recent INGESTED document.
 *
 * <p>If job parameter {@code caseIds} is provided (comma-separated UUIDs), the query is
 * further restricted to those case_ids. This allows
 * {@link DocumentVerificationScheduler} to trigger an
 * answer-generation run only for the cases that just became ready.
 */
@Slf4j
@Component
@StepScope
@RequiredArgsConstructor
public class ReadyCasePartitioner implements Partitioner {

    private static final String QUERY_READY_CASES_ALL = """
        SELECT DISTINCT ON (cd.case_id, cd.material_id)
               cd.case_id,
               cd.doc_id,
               cd.material_id,
               cd.ingestion_phase_at AS last_updated
          FROM case_documents cd
         WHERE cd.ingestion_phase = 'INGESTED'
         ORDER BY cd.case_id, cd.material_id, cd.ingestion_phase_at DESC
        """;

    private static final String QUERY_READY_CASES_FILTERED = """
        SELECT DISTINCT ON (cd.case_id, cd.material_id)
               cd.case_id,
               cd.doc_id,
               cd.material_id,
               cd.ingestion_phase_at AS last_updated
          FROM case_documents cd
         WHERE cd.ingestion_phase = 'INGESTED'
           AND cd.case_id IN (:case_ids)
         ORDER BY cd.case_id, cd.material_id, cd.ingestion_phase_at DESC
        """;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    /**
     * Optional job parameter from scheduler, comma-separated UUID list.
     * When null/blank, we select all INGESTED (case, material) combinations.
     */
    @Value("#{jobParameters['caseIds']}")
    private String caseIdsParameter;

    @Override
    @SuppressWarnings("PMD.UnusedFormalParameter")
    public Map<String, ExecutionContext> partition(final int gridSize) {
        final List<UUID> filteredCaseIds = parseCaseIds(this.caseIdsParameter);

        final String sql;
        final Map<String, Object> params = new HashMap<>();

        if (filteredCaseIds.isEmpty()) {
            sql = QUERY_READY_CASES_ALL;
            log.info("ReadyCasePartitioner: no caseIds job parameter; selecting all INGESTED cases.");
        } else {
            sql = QUERY_READY_CASES_FILTERED;
            params.put("case_ids", filteredCaseIds);
            log.info(
                    "ReadyCasePartitioner: restricting to {} caseId(s) from job parameter.",
                    Integer.valueOf(filteredCaseIds.size())
            );
        }

        final List<CaseStatusRow> caseStatusRows =
                this.namedParameterJdbcTemplate.query(sql, params, new CaseStatusRowMapper());

        final Map<String, ExecutionContext> partitions = new HashMap<>();
        int index = 0;

        for (final CaseStatusRow row : caseStatusRows) {
            final ExecutionContext executionContext = new ExecutionContext();
            executionContext.putString(CTX_CASE_ID_KEY, row.caseId().toString());
            executionContext.putString(CTX_DOC_ID_KEY, row.docId().toString());
            executionContext.putString(CTX_MATERIAL_ID_KEY, row.materialId().toString());
            executionContext.putString(
                    "ingestionPhaseAt",
                    row.lastUpdated() != null ? row.lastUpdated().toString() : null
            );

            final String partitionName = "case-partition-" + index;
            partitions.put(partitionName, executionContext);
            index++;
        }

        log.info(
                "ReadyCasePartitioner: created {} partition(s) for ready cases.",
                Integer.valueOf(partitions.size())
        );
        return partitions;
    }

    private List<UUID> parseCaseIds(final String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        final String[] tokens = raw.split(",");
        final List<UUID> result = new ArrayList<>(tokens.length);

        for (final String token : tokens) {
            final String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(UUID.fromString(trimmed));
            } catch (final IllegalArgumentException ex) {
                log.warn(
                        "ReadyCasePartitioner: ignoring invalid UUID '{}' in caseIds job parameter.",
                        trimmed
                );
            }
        }
        return result;
    }

    private record CaseStatusRow(
            UUID caseId,
            UUID docId,
            UUID materialId,
            OffsetDateTime lastUpdated
    ) {
    }

    private static final class CaseStatusRowMapper implements RowMapper<CaseStatusRow> {

        @Override
        public CaseStatusRow mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            final UUID caseId = (UUID) rs.getObject("case_id");
            final UUID docId = (UUID) rs.getObject("doc_id");
            final UUID materialId = (UUID) rs.getObject("material_id");

            OffsetDateTime lastUpdated = null;
            final Object raw = rs.getObject("last_updated");
            if (raw instanceof OffsetDateTime odt) {
                lastUpdated = odt;
            } else if (raw instanceof Timestamp ts) {
                lastUpdated = ts.toInstant().atOffset(ZoneOffset.UTC);
            }

            return new CaseStatusRow(caseId, docId, materialId, lastUpdated);
        }
    }
}
