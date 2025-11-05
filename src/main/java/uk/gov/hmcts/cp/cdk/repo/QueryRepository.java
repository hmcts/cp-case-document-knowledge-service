package uk.gov.hmcts.cp.cdk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.cp.cdk.domain.Query;

import java.util.Optional;
import java.util.UUID;

public interface QueryRepository extends JpaRepository<Query, UUID> {
    Optional<Query> findByLabelIgnoreCase(String label);
}
