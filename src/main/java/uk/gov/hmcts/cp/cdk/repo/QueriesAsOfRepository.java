package uk.gov.hmcts.cp.cdk.repo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imperative repository using native SQL. Not a Spring Data interface.
 */
@Repository
@Transactional(readOnly = true)
public interface QueriesAsOfRepository extends JpaRepository<uk.gov.hmcts.cp.cdk.domain.Query, UUID> {

    @org.springframework.data.jpa.repository.Query(value = """
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
                  q.query_id            AS queryId,
                  :caseId               AS caseId,
                  q.label               AS label,
                  ld.user_query         AS userQuery,
                  ld.query_prompt       AS queryPrompt,
                  ld.effective_at       AS effectiveAt,
                  cqs.status::text      AS status,
                  cqs.status_at         AS statusAt,
                  q.display_order       AS displayOrder
                FROM queries q
                JOIN latest_def ld
                  ON ld.query_id = q.query_id AND ld.rn = 1
                LEFT JOIN case_query_status cqs
                  ON cqs.query_id = q.query_id AND cqs.case_id = :caseId
                ORDER BY q.display_order ASC, q.query_id
            """,
            nativeQuery = true)
    List<QueriesAsOfRepository.QueryAsOfView> listForCaseAsOf(@Param("caseId") UUID caseId,
                                                              @Param("asOf") OffsetDateTime asOf);

    @Query(value = """
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
                  q.query_id            AS queryId,
                  :caseId               AS caseId,
                  q.label               AS label,
                  ld.user_query         AS userQuery,
                  ld.query_prompt       AS queryPrompt,
                  ld.effective_at       AS effectiveAt,
                  cqs.status::text      AS status,
                  cqs.status_at         AS statusAt,
                  q.display_order       AS displayOrder
                FROM queries q
                JOIN latest_def ld
                  ON ld.query_id = q.query_id AND ld.rn = 1
                LEFT JOIN case_query_status cqs
                  ON cqs.query_id = q.query_id AND cqs.case_id = :caseId
                WHERE q.query_id = :queryId
            """,
            nativeQuery = true)
    QueriesAsOfRepository.QueryAsOfView getOneForCaseAsOf(@Param("caseId") UUID caseId,
                                                          @Param("queryId") UUID queryId,
                                                          @Param("asOf") OffsetDateTime asOf);

    record QueryAsOfView(
            UUID queryId,
            UUID caseId,
            String label,
            String userQuery,
            String queryPrompt,
            Instant effectiveAt,
            String status,
            Instant statusAt,
            Integer displayOrder) {
    }
}
