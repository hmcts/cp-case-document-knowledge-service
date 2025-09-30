package uk.gov.hmcts.cp.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.cp.domain.QueryEntity;

import java.util.UUID;

public interface QueryRepository extends JpaRepository<QueryEntity, UUID> {
}
