package uk.gov.hmcts.cp.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.cp.domain.IngestionStatusHistoryEntity;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface IngestionStatusHistoryRepository
        extends JpaRepository<IngestionStatusHistoryEntity, Instant> {

    /**
     * Latest row by changedAt (global latest).
     */
    Optional<IngestionStatusHistoryEntity> findTopByOrderByChangedAtDesc();

    /**
     * Latest row with changedAt <= the given instant (as-of).
     */
    Optional<IngestionStatusHistoryEntity>
    findTopByChangedAtLessThanEqualOrderByChangedAtDesc(Instant instantAt);
}
