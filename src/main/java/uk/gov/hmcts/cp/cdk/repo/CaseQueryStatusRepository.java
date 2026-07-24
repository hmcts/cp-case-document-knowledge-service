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
}
