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

    static final String SQL_FIND_EXISTING_DOC = """
            SELECT doc_id
              FROM case_documents
             WHERE case_id = :case_id
               AND material_id = :material_id
               AND ingestion_phase IN ('UPLOADED','INGESTED','WAITING_FOR_UPLOAD')
             ORDER BY ingestion_phase_at DESC
             LIMIT 1
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
}
