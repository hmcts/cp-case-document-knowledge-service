package uk.gov.hmcts.cp.cdk.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Imperative repository using native SQL. Not a Spring Data interface.
 */
@Repository
@Transactional(readOnly = true)
public class QueriesAsOfRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @SuppressWarnings("unchecked")
    public List<Object[]> listForCaseAsOf(final UUID caseId, final OffsetDateTime asOf) {
        final String sql = """
                WITH latest_def AS (
                  SELECT
                    qv.query_id,
                    qv.user_query,
                    qv.query_prompt,
                    qv.effective_at,
                    ROW_NUMBER() OVER (PARTITION BY qv.query_id ORDER BY qv.effective_at DESC) AS rn
                  FROM query_versions qv
                  WHERE qv.effective_at <= :asOf
                )
                SELECT
                  q.query_id,
                  :caseId AS case_id,
                  q.label,
                  ld.user_query,
                  ld.query_prompt,
                  ld.effective_at,
                  cqs.status::text AS status,
                  cqs.status_at
                FROM queries q
                JOIN latest_def ld
                  ON ld.query_id = q.query_id AND ld.rn = 1
                LEFT JOIN case_query_status cqs
                  ON cqs.query_id = q.query_id AND cqs.case_id = :caseId
                ORDER BY q.query_id
                """;

        final Query nativeQuery = entityManager.createNativeQuery(sql);
        nativeQuery.setParameter("caseId", caseId);
        nativeQuery.setParameter("asOf", asOf);
        return (List<Object[]>) nativeQuery.getResultList();
    }

    public Object[] getOneForCaseAsOf(final UUID caseId, final UUID queryId, final OffsetDateTime asOf) {
        final String sql = """
                WITH latest_def AS (
                  SELECT
                    qv.query_id,
                    qv.user_query,
                    qv.query_prompt,
                    qv.effective_at,
                    ROW_NUMBER() OVER (PARTITION BY qv.query_id ORDER BY qv.effective_at DESC) AS rn
                  FROM query_versions qv
                  WHERE qv.query_id = :queryId
                    AND qv.effective_at <= :asOf
                )
                SELECT
                  q.query_id,
                  :caseId AS case_id,
                  q.label,
                  ld.user_query,
                  ld.query_prompt,
                  ld.effective_at,
                  cqs.status::text AS status,
                  cqs.status_at
                FROM queries q
                JOIN latest_def ld
                  ON ld.query_id = q.query_id AND ld.rn = 1
                LEFT JOIN case_query_status cqs
                  ON cqs.query_id = q.query_id AND cqs.case_id = :caseId
                WHERE q.query_id = :queryId
                """;

        final Query nativeQuery = entityManager.createNativeQuery(sql);
        nativeQuery.setParameter("caseId", caseId);
        nativeQuery.setParameter("queryId", queryId);
        nativeQuery.setParameter("asOf", asOf);

        @SuppressWarnings("unchecked") final List<Object[]> resultRows = nativeQuery.getResultList();
        return resultRows.isEmpty() ? null : resultRows.get(0);
    }
}
