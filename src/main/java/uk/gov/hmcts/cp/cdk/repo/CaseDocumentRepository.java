package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.CaseDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CaseDocumentRepository extends JpaRepository<CaseDocument, UUID> {

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

