package uk.gov.hmcts.cp.cdk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface QueryVersionRepository extends JpaRepository<QueryVersion, QueryVersionId> {

    @Query(value = """
        SELECT q.query_id    AS queryId,
               q.label       AS label,
               v.user_query  AS userQuery,
               v.query_prompt AS queryPrompt,
               v.effective_at AS effectiveAt
          FROM queries q
          JOIN LATERAL (
             SELECT *
               FROM query_versions v
              WHERE v.query_id = q.query_id
                AND v.effective_at <= :asOf
              ORDER BY v.effective_at DESC
              LIMIT 1
          ) v ON TRUE
         ORDER BY q.query_id
        """, nativeQuery = true)
    List<Object[]> snapshotDefinitionsAsOf(OffsetDateTime asOf);

    @Query(value = """
        SELECT v.*
          FROM query_versions v
         WHERE v.query_id = :queryId
         ORDER BY v.effective_at ASC
        """, nativeQuery = true)
    List<QueryVersion> findAllVersions(UUID queryId);
}
