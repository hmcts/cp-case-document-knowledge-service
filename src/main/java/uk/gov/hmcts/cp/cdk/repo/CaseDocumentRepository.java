package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.CaseDocument;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CaseDocumentRepository extends JpaRepository<CaseDocument, UUID> {

    /**
     * DD-43185 (FR-005, ADR-004): counts stalled documents grouped by phase, for the accepted
     * monitored phase set {@code WAITING_FOR_UPLOAD, UPLOADING, UPLOADED, INGESTING} — the ticket's
     * original three plus {@code UPLOADED}. Selects only {@code ingestion_phase}/
     * {@code ingestion_phase_at}; served by {@code idx_cd_phase_phase_at} (V1014), which is a plain
     * (non-partial) composite so it is not coupled to this phase list.
     */
    String COUNT_STALLED_BY_PHASE_SQL = """
            SELECT cd.ingestion_phase::text AS phase, COUNT(*) AS total
              FROM case_documents cd
             WHERE cd.ingestion_phase IN ('WAITING_FOR_UPLOAD','UPLOADING','UPLOADED','INGESTING')
               AND cd.ingestion_phase_at < :cutoff
             GROUP BY cd.ingestion_phase
            """;

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query(value = COUNT_STALLED_BY_PHASE_SQL, nativeQuery = true)
    List<PhaseCount> countStalledByPhase(@Param("cutoff") OffsetDateTime cutoff);

    Optional<CaseDocument> findFirstByCaseIdOrderByUploadedAtDesc(UUID caseId);

    @Query(value = """
             SELECT distinct(cd.doc_id)
               FROM case_documents cd 
             WHERE cd.case_id = :caseId 
               AND cd.defendant_id = :defendantId 
               AND cd.ingestion_phase = 'INGESTED'
            """, nativeQuery = true)
    List<UUID> findSupersededDocuments(UUID caseId, UUID defendantId);

    /**
     * This method handles the scenarios when defendant_id was not populated and processed only
     * those cases with single defendant.
     * This is a fallback method to find superseded documents for legacy cases
     */
    @Query(value = """
             SELECT distinct(cd.doc_id)
               FROM case_documents cd 
             WHERE cd.case_id = :caseId 
               AND cd.defendant_id IS NULL 
               AND cd.ingestion_phase = 'INGESTED'
            """, nativeQuery = true)
    List<UUID> findSupersededDocuments(UUID caseId);

}

