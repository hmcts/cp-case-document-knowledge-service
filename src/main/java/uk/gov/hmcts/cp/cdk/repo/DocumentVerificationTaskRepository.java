package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus;
import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationTask;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA repository for document verification tasks.
 */
public interface DocumentVerificationTaskRepository extends JpaRepository<DocumentVerificationTask, Long> {

    Optional<DocumentVerificationTask> findByDocId(UUID documentId);

    long countByStatus(DocumentVerificationStatus status);
}
