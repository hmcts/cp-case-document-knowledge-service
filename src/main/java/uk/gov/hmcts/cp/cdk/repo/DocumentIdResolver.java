package uk.gov.hmcts.cp.cdk.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves the authoritative {@code doc_id} from the database for a given {@code (case_id, material_id)}.
 * Prefers documents that are already uploaded/ingesting/ingested, ordered by most recent ingestion phase time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIdResolver {

    /* default */
    static final String SQL_FIND_EXISTING_DOC = """
            SELECT doc_id
              FROM case_documents
             WHERE case_id = :case_id
               AND material_id = :material_id
               AND ingestion_phase IN ('UPLOADED','INGESTED','WAITING_FOR_UPLOAD')
             ORDER BY ingestion_phase_at DESC
             LIMIT 1
            """;

    /* default */
    static final String SQL_FIND_EXISTING_DOC_FOR_DEFENDANT = """
        SELECT doc_id
          FROM case_documents
         WHERE case_id = :case_id
           AND material_id = :material_id
           AND defendant_id = :defendant_id
           AND ingestion_phase IN ('UPLOADED','INGESTED','WAITING_FOR_UPLOAD')
         ORDER BY ingestion_phase_at DESC
         LIMIT 1
        """;

    /* default */
    static final String SQL_FIND_INGESTION_STATUS = """
    SELECT ingestion_phase
      FROM case_documents
     WHERE doc_id = :doc_id
    """;

    /* default */
    static final String SQL_FIND_INGESTION_STATUS_ALL_DOCS = """
        SELECT DISTINCT doc_id
          FROM case_documents
         WHERE doc_id IN (:doc_ids)
           AND ingestion_phase = 'INGESTED'
        """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Attempts to resolve an existing document id for the given case and material.
     * Never throws; logs and returns {@link Optional#empty()} on error.
     */
    public Optional<UUID> resolveExistingDocId(final UUID caseId, final UUID materialId) {
        Optional<UUID> result = Optional.empty();

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("material_id", materialId);

        final List<UUID> rows = jdbc.query(SQL_FIND_EXISTING_DOC, params,
                (rs, rowNum) -> (UUID) rs.getObject(1));
        if (!rows.isEmpty()) {
            result = Optional.ofNullable(rows.get(0));
        }

        return result;
    }

    /**
     * Attempts to resolve an existing document id for the given case, material, and defendant.
     * Never throws; logs and returns {@link Optional#empty()} on error.
     */
    public Optional<UUID> resolveExistingDocIdForDefendant(final UUID caseId,
                                                           final UUID materialId,
                                                           final UUID defendantId) {
        Optional<UUID> result = Optional.empty();

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("material_id", materialId)
                .addValue("defendant_id", defendantId);

        final List<UUID> rows = jdbc.query(SQL_FIND_EXISTING_DOC_FOR_DEFENDANT, params,
                (rs, rowNum) -> (UUID) rs.getObject(1));

        if (!rows.isEmpty()) {
            result = Optional.ofNullable(rows.get(0));
        }

        return result;
    }

    public Optional<String> findIngestionStatus(final UUID docId) {

        try {
            final MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("doc_id", docId);

            final List<String> rows = jdbc.query(SQL_FIND_INGESTION_STATUS, params,
                    (rs, rowNum) -> rs.getString("ingestion_phase")
            );

            return rows.stream().findFirst();

        } catch (Exception e) {
            log.error("Failed to fetch ingestion status for docId={}", docId, e);
            return Optional.empty();
        }
    }

    /**
     * Checks if all given docIds have ingestion_phase = 'INGESTED'.
     * Returns true only if all documents are INGESTED; false otherwise.
     */
    public boolean findIngestionStatusForAllDocs(final List<UUID> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return false;
        }
        try {
            final MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("doc_ids", docIds);

            final List<UUID> ingestedDocs = jdbc.query(SQL_FIND_INGESTION_STATUS_ALL_DOCS, params,
                    (rs, rowNum) -> (UUID) rs.getObject("doc_id"));

            // If number of ingested distinct docs matches the input list, all are INGESTED
            return ingestedDocs.size() == docIds.size();

        } catch (Exception e) {
            log.error("Failed to check ingestion status for docIds={}", docIds, e);
            return false;
        }
    }
}
