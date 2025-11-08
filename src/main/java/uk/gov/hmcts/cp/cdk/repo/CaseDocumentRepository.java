package uk.gov.hmcts.cp.cdk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaseDocumentRepository extends JpaRepository<CaseDocument, UUID> {

    Optional<CaseDocument> findFirstByCaseIdOrderByUploadedAtDesc(UUID caseId);

    boolean existsByCaseIdAndMaterialIdAndIngestionPhaseIn(UUID caseId,
                                                           UUID materialId,
                                                           Collection<DocumentIngestionPhase> phases);

    boolean existsByDocId(UUID docId);

}

