package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatus;
import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatusId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseQueryStatusRepository extends JpaRepository<CaseQueryStatus, CaseQueryStatusId> {

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
