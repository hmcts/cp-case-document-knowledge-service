package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QueryDefinitionLatestRepository extends JpaRepository<QueryDefinitionLatest, UUID> {
    Optional<QueryDefinitionLatest> findByQueryId(UUID queryId);
}
