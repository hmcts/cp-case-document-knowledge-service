package uk.gov.hmcts.cp.cdk.batch.verification;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.ROOM_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.RUN_ID;
import static uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus.FAILED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus.SUCCEEDED;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.config.VerifySchedulerProperties;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus;
import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationTask;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentVerificationTaskRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully.StatusEnum;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background scheduler that polls IDPC ingestion status for queued documents.
 *
 * <p>On INGESTION_SUCCESS it:
 * <ul>
 *   <li>Marks the verification task as {@link DocumentVerificationStatus#SUCCEEDED}</li>
 *   <li>Updates {@code case_documents.ingestion_phase} to {@link DocumentIngestionPhase#INGESTED}</li>
 *   <li>Triggers {@code answerGenerationJob} via {@link JobOperator}, passing only the
 *       caseIds that reached INGESTION_SUCCESS in this poll.</li>
 * </ul>
 *
 * <p>Locking / distribution:
 * <ul>
 *   <li>{@link DocumentVerificationQueueDao#claimBatch(String, int)} ensures only one instance
 *       processes a given task at a time.</li>
 *   <li>Multiple app instances can run this scheduler safely – each instance has a unique owner id.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVerificationScheduler {

    private static final String ANSWER_GENERATION_JOB_NAME = "answerGenerationJob";
    private static final String LOG_PREFIX = "DocumentVerificationScheduler: ";

    // Defensive caps to remain compatible with varchar(255) columns.
    private static final int MAX_STATUS_LENGTH = 255;
    private static final int MAX_REASON_LENGTH = 255;

    private final VerifySchedulerProperties verifySchedulerProperties;
    private final DocumentVerificationQueueDao documentVerificationQueueDao;
    private final DocumentVerificationTaskRepository documentVerificationTaskRepository;
    private final DocumentIngestionStatusApi documentIngestionStatusApi;
    private final CaseDocumentRepository caseDocumentRepository;
    private final PlatformTransactionManager platformTransactionManager;
    private final JobOperator jobOperator;
    private final Job answerGenerationJob;

    @Value("${spring.application.name:cp-case-document-knowledge-service}")
    private String applicationName;

    /**
     * Random instance id so that lock owner is unique per JVM.
     */
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * Polls queued verification tasks and processes them.
     * Claims at most {@code batchSize} rows per poll, per instance.
     */
    @Scheduled(fixedDelayString = "${cdk.ingestion.verify.scheduler.delay-ms:5000}")
    public void pollPendingDocuments() {
        if (!this.verifySchedulerProperties.isEnabled()) {
            return;
        }

        final String owner = this.applicationName + "-" + this.instanceId;
        final int batchSize = this.verifySchedulerProperties.getBatchSize();

        final List<DocumentVerificationTask> tasks =
                this.documentVerificationQueueDao.claimBatch(owner, batchSize);

        if (tasks.isEmpty()) {
            return;
        }

        log.debug(
                LOG_PREFIX + "claimed {} task(s) for owner={}",
                Integer.valueOf(tasks.size()),
                owner
        );

        final Set<DocumentVerificationTask> succeededTasks = new LinkedHashSet<>();
        for (final DocumentVerificationTask task : tasks) {
            try {
                final boolean succeeded = processTask(task);
                if (succeeded) {
                    succeededTasks.add(task);
                }
            } catch (final RuntimeException exception) {
                log.warn(
                        LOG_PREFIX + "unexpected error while processing task id={} docId={}: {}",
                        task.getId(),
                        task.getDocId(),
                        exception.getMessage(),
                        exception
                );
                handleSchedulerFailure(task, "EXCEPTION", exception.getMessage(), null);
            }
        }

        if (!succeededTasks.isEmpty()) {
            triggerAnswerGenerationJob(succeededTasks);
        }
    }

    /**
     * Process a single verification task.
     *
     * @return {@code true} if this task reached INGESTION_SUCCESS (terminal success), {@code false} otherwise.
     */
    private boolean processTask(final DocumentVerificationTask task) {
        final UUID documentId = task.getDocId();
        final UUID caseId = task.getCaseId();
        final String identifier = task.getBlobName();

        log.debug(
                LOG_PREFIX + "polling ingestion status for identifier='{}' (caseId={}, docId={})",
                identifier,
                caseId,
                documentId
        );

        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> response =
                this.documentIngestionStatusApi.documentStatus(identifier);

        if (response == null) {
            throw new IllegalStateException(
                    "Null HTTP response from documentStatus for identifier=" + identifier
            );
        }

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return handleSuccessfulHttpResponse(task, documentId, caseId, identifier, response);
        }

        if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
            log.debug(
                    LOG_PREFIX + "identifier='{}' not found yet (404); scheduling retry.",
                    identifier
            );
            scheduleRetry(task, "404_NOT_FOUND", null, null);
            return false;
        }

        log.warn(
                LOG_PREFIX + "unexpected HTTP status {} for identifier='{}'. Scheduling retry.",
                response.getStatusCode(),
                identifier
        );
        scheduleRetry(task, "HTTP_" + response.getStatusCode().value(), null, null);
        return false;
    }

    /**
     * Handle HTTP 2xx response from IDPC.
     *
     * @return {@code true} if status is INGESTION_SUCCESS (terminal success), {@code false} otherwise.
     */
    private boolean handleSuccessfulHttpResponse(
            final DocumentVerificationTask task,
            final UUID documentId,
            final UUID caseId,
            final String identifier,
            final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> response
    ) {
        final DocumentIngestionStatusReturnedSuccessfully body = response.getBody();
        if (body == null) {
            throw new IllegalStateException(
                    "HTTP 2xx but response body is null for identifier=" + identifier
            );
        }

        final String rawStatus = body.getStatus() != null ? body.getStatus().getValue() : null;
        final String rawReason = body.getReason();
        final OffsetDateTime lastUpdated = body.getLastUpdated();

        if (rawStatus == null) {
            scheduleRetry(task, "UNKNOWN", rawReason, lastUpdated);
            return false;
        }

        final String statusValue = normaliseStatus(rawStatus);
        final String reasonValue = normaliseReason(rawReason);

        if (StatusEnum.INGESTION_SUCCESS.name().equalsIgnoreCase(statusValue)) {
            updateIngestionPhaseInSeparateTransaction(documentId, DocumentIngestionPhase.INGESTED);
            markSucceeded(task, statusValue, reasonValue, lastUpdated);
            log.info(
                    LOG_PREFIX + "ingestion SUCCESS for identifier='{}' (caseId={}, docId={}).",
                    identifier,
                    caseId,
                    documentId
            );
            return true;
        }

        if (StatusEnum.INGESTION_FAILED.name().equalsIgnoreCase(statusValue)
                || StatusEnum.INVALID_METADATA.name().equalsIgnoreCase(statusValue)) {

            updateIngestionPhaseInSeparateTransaction(documentId, DocumentIngestionPhase.FAILED);
            markFailed(task, statusValue, reasonValue, lastUpdated);
            log.error(
                    LOG_PREFIX + "ingestion FAILED for identifier='{}' reason='{}' (caseId={}, docId={}).",
                    identifier,
                    reasonValue,
                    caseId,
                    documentId
            );
            return false;
        }

        // Any other status – keep polling until max attempts reached
        scheduleRetry(task, statusValue, reasonValue, lastUpdated);
        return false;
    }

    private void scheduleRetry(
            final DocumentVerificationTask task,
            final String lastStatus,
            final String lastReason,
            final OffsetDateTime lastUpdated
    ) {

        final int nextAttemptCount = task.getAttemptCount() + 1;
        final int maxAttempts = task.getMaxAttempts();

        task.setAttemptCount(nextAttemptCount);
        task.setLastStatus(normaliseStatus(lastStatus));
        task.setLastReason(normaliseReason(lastReason));
        task.setLastStatusTimestamp(
                lastUpdated != null ? lastUpdated : utcNow()
        );

        if (nextAttemptCount >= maxAttempts) {
            task.setStatus(DocumentVerificationStatus.FAILED);
            task.setNextAttemptAt(utcNow());
            task.setLockOwner(null);
            task.setLockAcquiredAt(null);
            ensureTimestamps(task);
            this.documentVerificationTaskRepository.saveAndFlush(task);

            log.warn(
                    LOG_PREFIX + "giving up after {} attempt(s) for docId={} (lastStatus={})",
                    Integer.valueOf(nextAttemptCount),
                    task.getDocId(),
                    task.getLastStatus()
            );
        } else {
            final OffsetDateTime nextAttemptAt =
                    utcNow().plus(
                            Duration.ofMillis(this.verifySchedulerProperties.getDelayMs())
                    );

            task.setStatus(DocumentVerificationStatus.IN_PROGRESS);
            task.setNextAttemptAt(nextAttemptAt);
            task.setLockOwner(null);
            task.setLockAcquiredAt(null);
            ensureTimestamps(task);
            this.documentVerificationTaskRepository.saveAndFlush(task);
        }
    }

    private void markSucceeded(
            final DocumentVerificationTask task,
            final String lastStatus,
            final String lastReason,
            final OffsetDateTime lastUpdated
    ) {
        task.setStatus(SUCCEEDED);

        task.setLastStatus(normaliseStatus(lastStatus));
        task.setLastReason(normaliseReason(lastReason));
        task.setLastStatusTimestamp(
                lastUpdated != null ? lastUpdated : utcNow()
        );
        task.setNextAttemptAt(utcNow());
        task.setLockOwner(null);
        task.setLockAcquiredAt(null);

        ensureTimestamps(task);
        this.documentVerificationTaskRepository.saveAndFlush(task);
    }

    private void markFailed(
            final DocumentVerificationTask task,
            final String lastStatus,
            final String lastReason,
            final OffsetDateTime lastUpdated
    ) {
        task.setStatus(FAILED);
        task.setLastStatus(normaliseStatus(lastStatus));
        task.setLastReason(normaliseReason(lastReason));
        task.setLastStatusTimestamp(
                lastUpdated != null ? lastUpdated : utcNow()
        );
        task.setNextAttemptAt(utcNow());
        task.setLockOwner(null);
        task.setLockAcquiredAt(null);

        ensureTimestamps(task);
        this.documentVerificationTaskRepository.saveAndFlush(task);
    }

    private void handleSchedulerFailure(
            final DocumentVerificationTask task,
            final String syntheticStatus,
            final String reason,
            final OffsetDateTime lastUpdated
    ) {
        scheduleRetry(task, syntheticStatus, reason, lastUpdated);
    }

    private void updateIngestionPhaseInSeparateTransaction(
            final UUID documentId,
            final DocumentIngestionPhase targetPhase
    ) {
        final TransactionTemplate transactionTemplate =
                new TransactionTemplate(this.platformTransactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.setTimeout(5);

        try {
            transactionTemplate.execute(transactionStatus -> {
                final java.util.Optional<CaseDocument> caseDocumentOptional =
                        this.caseDocumentRepository.findById(documentId);

                caseDocumentOptional.ifPresent(caseDocument -> {
                    caseDocument.setIngestionPhase(targetPhase);
                    caseDocument.setIngestionPhaseAt(utcNow());
                    this.caseDocumentRepository.saveAndFlush(caseDocument);
                    log.info(
                            LOG_PREFIX + "updated ingestion phase to {} for docId={}",
                            targetPhase,
                            documentId
                    );
                });
                return null;
            });
        } catch (final RuntimeException exception) {
            log.warn(
                    LOG_PREFIX + "failed to persist ingestion phase {} for docId={}: {}",
                    targetPhase,
                    documentId,
                    exception.getMessage()
            );
        }
    }

    /**
     * Trigger answerGenerationJob once per poll where at least one document succeeded.
     *
     * <p>We pass only the caseIds whose documents just reached INGESTION_SUCCESS in this poll
     * (comma-separated) as job parameter {@code caseIds}. {@code ReadyCasePartitioner}
     * reads that parameter and still filters by {@code ingestion_phase = 'INGESTED'}
     * in {@code case_documents}.
     */
    private void triggerAnswerGenerationJob(final Set<DocumentVerificationTask> succeededTasks) {
        final JobParameters parameters = buildJobParameters(succeededTasks);
        final String caseIdsParameter = parameters.getString("caseIds", "");

        try {
            final JobExecution execution =
                    this.jobOperator.start(this.answerGenerationJob, parameters);
            final Long executionId = execution.getId();

            log.info(
                    LOG_PREFIX + "triggered {} with executionId={} caseIds={}",
                    ANSWER_GENERATION_JOB_NAME,
                    executionId,
                    caseIdsParameter
            );
        } catch (final JobExecutionException exception) {
            log.error(
                    LOG_PREFIX + "failed to start {} with params={}: {}",
                    ANSWER_GENERATION_JOB_NAME,
                    caseIdsParameter,
                    exception.getMessage(),
                    exception
            );
            for (DocumentVerificationTask task : succeededTasks) {
                scheduleRetry(
                        task,
                        DocumentVerificationStatus.IN_PROGRESS.name(),
                        exception.getMessage(),
                        utcNow()
                );
            }
        }
    }

    private JobParameters buildJobParameters(final Set<DocumentVerificationTask> succeededTasks) {
        final String triggerId = UUID.randomUUID().toString();

        final String caseIdsParameter = succeededTasks.stream()
                .map(DocumentVerificationTask::getCaseId)
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .distinct()
                .collect(Collectors.joining(","));
        log.info(
                LOG_PREFIX + "triggerId {} with caseIds={}: {}",
                ANSWER_GENERATION_JOB_NAME,
                triggerId,
                caseIdsParameter
        );
        return new JobParametersBuilder()
                // IDENTIFYING parameters -> define the JobInstance
                .addLong(RUN_ID, System.currentTimeMillis(), true)
                .addString("triggerId", triggerId, true)
                .addString("caseIds", caseIdsParameter, true)

                // NON-IDENTIFYING parameters -> metadata only
                .addLong("ts", System.currentTimeMillis(), false)
                .addString("trigger", "DocumentVerificationScheduler", false)
                .toJobParameters();
    }

    // ---------- helpers ----------

    private void ensureTimestamps(final DocumentVerificationTask task) {
        final OffsetDateTime now = utcNow();
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(now);
        }
        task.setUpdatedAt(now);
    }

    private String normaliseStatus(final String status) {
        if (status == null) {
            return null;
        }
        if (status.length() <= MAX_STATUS_LENGTH) {
            return status;
        }
        return status.substring(0, MAX_STATUS_LENGTH);
    }

    private String normaliseReason(final String reason) {
        if (reason == null) {
            return null;
        }
        if (reason.length() <= MAX_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_REASON_LENGTH);
    }
}
