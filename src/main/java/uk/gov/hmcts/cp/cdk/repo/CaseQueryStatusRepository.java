package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatus;
import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatusId;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface CaseQueryStatusRepository extends JpaRepository<CaseQueryStatus, CaseQueryStatusId> {

    /**
     * DD-43185 (FR-005): counts queries stuck awaiting an answer, older than the given cutoff.
     * {@code status} is spelled as a literal, not a bound parameter — the {@code V1014} partial
     * index {@code idx_cqs_awaiting_answer_at} only applies when PostgreSQL can trivially prove
     * the predicate, which holds for a literal equality but is not reliable for a bound parameter.
     */
    String COUNT_AWAITING_ANSWER_OLDER_THAN_SQL = """
            SELECT COUNT(*)
              FROM case_query_status cqs
             WHERE cqs.status = 'ANSWER_NOT_AVAILABLE'
               AND cqs.status_at < :cutoff
            """;

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query(value = COUNT_AWAITING_ANSWER_OLDER_THAN_SQL, nativeQuery = true)
    long countAwaitingAnswerOlderThan(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * {@code caseId} lives on the {@link CaseQueryStatusId} embedded id, not directly on
     * {@link CaseQueryStatus} — a derived {@code findByCaseId} would resolve against the entity's
     * plain {@code getCaseId()} convenience getter instead, which isn't a mapped JPA attribute, so
     * the path is spelled out explicitly here.
     */
    @Query("SELECT c FROM CaseQueryStatus c WHERE c.caseQueryStatusId.caseId = :caseId")
    List<CaseQueryStatus> findByCaseId(@Param("caseId") UUID caseId);

    @Query("SELECT c FROM CaseQueryStatus c "
            + "WHERE c.caseQueryStatusId.caseId = :caseId AND c.caseQueryStatusId.queryId = :queryId")
    Optional<CaseQueryStatus> findByCaseIdAndQueryId(@Param("caseId") UUID caseId, @Param("queryId") UUID queryId);

    /**
     * True if an {@code ANSWER_AVAILABLE} status is recorded against the case's latest IDPC
     * document (most recently uploaded {@code case_documents} row still in an active ingestion
     * phase). Resolves the latest doc_id and checks it in a single round trip rather than two
     * separate repository calls.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                  FROM case_query_status cqs
                 WHERE cqs.case_id = :caseId
                   AND cqs.status = 'ANSWER_AVAILABLE'
                   AND cqs.doc_id = (
                         SELECT cd.doc_id
                           FROM case_documents cd
                          WHERE cd.case_id = :caseId
                            AND cd.ingestion_phase IN ('UPLOADED','INGESTED','WAITING_FOR_UPLOAD','EXCEEDED_FILE_SIZE_LIMIT')
                          ORDER BY cd.uploaded_at DESC
                          LIMIT 1
                       )
            )
            """, nativeQuery = true)
    boolean existsAnswerAvailableForLatestDoc(@Param("caseId") UUID caseId);
}
