package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.AnswerId;
import uk.gov.hmcts.cp.cdk.domain.CaseLevelLatestDocumentAnswer;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CaseLevelLatestDocumentAnswerRepository extends JpaRepository<CaseLevelLatestDocumentAnswer, AnswerId> {

    @Query(value = """
            SELECT a.*
              FROM case_level_latest_doc_answers a
             WHERE a.query_id = :queryId
               AND a.created_at <= :asOf
             ORDER BY a.created_at DESC, a.version DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<CaseLevelLatestDocumentAnswer> findLatestAsOfAnyCase(UUID queryId, OffsetDateTime asOf);

    @Query(value = """
            SELECT a.*
              FROM case_level_latest_doc_answers a
             WHERE a.case_id = :caseId
               AND a.query_id = :queryId
               AND a.created_at <= :asOf
             ORDER BY a.created_at DESC, a.version DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<CaseLevelLatestDocumentAnswer> findLatestAsOfForCase(UUID caseId, UUID queryId, OffsetDateTime asOf);

    @Query(value = """
            SELECT a.*
              FROM case_level_latest_doc_answers a
             WHERE a.case_id = :caseId
               AND a.query_id = :queryId
               AND a.version = :version
             LIMIT 1
            """, nativeQuery = true)
    Optional<CaseLevelLatestDocumentAnswer> findByCaseAndVersion(UUID caseId, UUID queryId, int version);

    @Query(value = """
            SELECT COUNT(DISTINCT a.case_id)
              FROM case_level_latest_doc_answers a
             WHERE a.query_id = :queryId
            """, nativeQuery = true)
    long countDistinctCasesForQuery(UUID queryId);
}