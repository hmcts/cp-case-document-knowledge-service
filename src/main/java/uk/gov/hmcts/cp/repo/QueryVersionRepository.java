package uk.gov.hmcts.cp.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uk.gov.hmcts.cp.domain.QueryVersionEntity;
import uk.gov.hmcts.cp.domain.QueryVersionKey;

import java.time.Instant;
import java.util.List;

public interface QueryVersionRepository extends JpaRepository<QueryVersionEntity, QueryVersionKey> {

    // Latest version for every query (no filter)
    @Query("""
               SELECT q FROM QueryVersionEntity q
               WHERE q.id.effectiveAt = (
                 SELECT MAX(q2.id.effectiveAt)
                 FROM QueryVersionEntity q2
                 WHERE q2.id.queryId = q.id.queryId
               )
            """)
    List<QueryVersionEntity> findLatestAll();

    // Latest version for every query as-of a timestamp
    @Query("""
               SELECT q FROM QueryVersionEntity q
               WHERE q.id.effectiveAt = (
                 SELECT MAX(q2.id.effectiveAt)
                 FROM QueryVersionEntity q2
                 WHERE q2.id.queryId = q.id.queryId AND q2.id.effectiveAt <= :asOf
               )
            """)
    List<QueryVersionEntity> findLatestAsOf(Instant asOf);
}
