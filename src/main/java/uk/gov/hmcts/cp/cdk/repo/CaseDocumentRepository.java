package uk.gov.hmcts.cp.cdk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;

import java.util.Optional;
import java.util.UUID;

public interface CaseDocumentRepository extends JpaRepository<CaseDocument, UUID> {
    Optional<CaseDocument> findFirstByCaseIdOrderByUploadedAtDesc(UUID caseId);
}
