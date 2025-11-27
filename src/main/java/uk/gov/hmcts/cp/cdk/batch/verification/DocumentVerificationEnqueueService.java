package uk.gov.hmcts.cp.cdk.batch.verification;

import static uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus.PENDING;

import uk.gov.hmcts.cp.cdk.config.VerifySchedulerProperties;
import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationTask;
import uk.gov.hmcts.cp.cdk.repo.DocumentVerificationTaskRepository;
import uk.gov.hmcts.cp.cdk.util.TimeUtils;

import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Enqueues document verification tasks for the scheduler.
 * <p>
 * One row per document upload. The scheduler will:
 * - poll IDPC for ingestion status
 * - update {@code case_documents.ingestion_phase}
 * - trigger the answerGenerationJob when documents reach INGESTED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentVerificationEnqueueService {

    private final DocumentVerificationTaskRepository documentVerificationTaskRepository;
    private final VerifySchedulerProperties verifySchedulerProperties;

    /**
     * Enqueue a new verification task for the given document.
     *
     * @param caseId   case identifier
     * @param docId    document identifier
     * @param blobName blob name in storage
     */
    public void enqueue(final UUID caseId, final UUID docId, final String blobName) {
        final OffsetDateTime now = TimeUtils.utcNow();

        final DocumentVerificationTask task = new DocumentVerificationTask();
        task.setDocId(docId);
        task.setCaseId(caseId);
        task.setBlobName(blobName);

        task.setAttemptCount(0);
        task.setMaxAttempts(verifySchedulerProperties.getMaxAttempts());

        task.setStatus(PENDING);
        task.setLastStatus(null);
        task.setLastReason(null);
        task.setLastStatusTimestamp(null);

        task.setNextAttemptAt(now);
        task.setLockOwner(null);
        task.setLockAcquiredAt(null);

        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        this.documentVerificationTaskRepository.save(task);

        log.info(
                "Enqueued document verification task: id={}, caseId={}, docId={}, blobName={}, maxAttempts={}",
                task.getId(),
                caseId,
                docId,
                blobName,
                Integer.valueOf(task.getMaxAttempts())
        );
    }
}
