package uk.gov.hmcts.cp.cdk.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.cdk.util.TimeUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Native/imperative repository over the view v_case_ingestion_status.
 * This is NOT a Spring Data JPA repository interface.
 */
@Repository
@Transactional(readOnly = true)
public class IngestionStatusViewRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public record Row(UUID caseId, String phase, OffsetDateTime lastUpdated) { }

    public Optional<Row> findByCaseId(final UUID caseId) {
        final String sql = """
            SELECT case_id, phase, last_updated
              FROM v_case_ingestion_status
             WHERE case_id = :caseId
            """;

        final Query nativeQuery = entityManager.createNativeQuery(sql);
        nativeQuery.setParameter("caseId", caseId);

        @SuppressWarnings("unchecked")
        final List<Object[]> resultRows = nativeQuery.getResultList();

        final Optional<Row> result;
        if (resultRows.isEmpty()) {
            result = Optional.empty();
        } else {
            final Object[] firstRow = resultRows.get(0);
            final UUID foundCaseId = (UUID) firstRow[0];
            final String phaseText = firstRow[1] == null ? null : firstRow[1].toString();
            final OffsetDateTime lastUpdatedUtc = TimeUtils.toUtc(firstRow[2]);
            result = Optional.of(new Row(foundCaseId, phaseText, lastUpdatedUtc));
        }
        return result;
    }
}
