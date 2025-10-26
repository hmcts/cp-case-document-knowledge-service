package uk.gov.hmcts.cp.cdk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatus;
import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatusId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseQueryStatusRepository extends JpaRepository<CaseQueryStatus, CaseQueryStatusId> {
    List<CaseQueryStatus> findByCaseId(UUID caseId);

    Optional<CaseQueryStatus> findByCaseIdAndQueryId(UUID caseId, UUID queryId);
}
