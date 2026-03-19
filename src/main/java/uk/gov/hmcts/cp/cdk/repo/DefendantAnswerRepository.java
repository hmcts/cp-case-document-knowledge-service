package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.DefendantAnswer;
import uk.gov.hmcts.cp.cdk.domain.DefendantAnswerId;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DefendantAnswerRepository extends JpaRepository<DefendantAnswer, DefendantAnswerId> {

    @Query(value = """
            SELECT a.*
              FROM defendant_answers a
             WHERE a.query_id = :queryId
               AND a.created_at <= :asOf
             ORDER BY a.created_at DESC, a.version DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<DefendantAnswer> findLatestAsOfAnyCase(UUID queryId, OffsetDateTime asOf);

    @Query(value = """
            SELECT a.*
              FROM defendant_answers a
             WHERE a.case_id = :caseId
               AND a.query_id = :queryId
               AND a.defendant_id = :defendantId
               AND a.created_at <= :asOf
             ORDER BY a.created_at DESC, a.version DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<DefendantAnswer> findLatestAsOfForDefendant(UUID caseId, UUID queryId, UUID defendantId, OffsetDateTime asOf);

    @Query(value = """
            SELECT a.*
              FROM defendant_answers a
             WHERE a.case_id = :caseId
               AND a.query_id = :queryId
               AND a.defendant_id = :defendantId
               AND a.version = :version
             LIMIT 1
            """, nativeQuery = true)
    Optional<DefendantAnswer> findByCaseDefendantAndVersion(UUID caseId, UUID queryId, UUID defendantId, int version);

    @Query(value = """
            SELECT COUNT(DISTINCT a.case_id)
              FROM defendant_answers a
             WHERE a.query_id = :queryId
            """, nativeQuery = true)
    long countDistinctCasesForQuery(UUID queryId);
}