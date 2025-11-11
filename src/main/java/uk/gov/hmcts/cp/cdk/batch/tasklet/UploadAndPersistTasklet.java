package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NEW_UPLOAD;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskLookupUtils.parseUuidOrNull;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.UploadProperties;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UploadAndPersistTasklet implements Tasklet {

    private static final String META_CASE_ID = "case_id";
    private static final String META_MATERIAL_ID = "material_id";
    private static final String META_MATERIAL_NAME = "material_name";
    private static final String META_UPLOADED_AT = "uploaded_at";
    private static final String META_DOCUMENT_ID = "document_id";
    private static final String META_METADATA = "metadata";

    private static final int MIN_RETRY_LOG_ATTEMPT = 2;
    private static final long UNKNOWN_SIZE_BYTES = -1L;

    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final CaseDocumentRepository caseDocumentRepository;
    private final UploadProperties uploadProperties;
    private final RetryTemplate retryTemplate;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        boolean proceed = true;

        final StepExecution stepExecution = contribution != null ? contribution.getStepExecution() : null;
        final ExecutionContext stepContext;
        final JobExecution jobExecution;
        if (stepExecution == null) {
            log.warn("UploadAndPersistTasklet invoked without StepExecution; nothing to do.");
            proceed = false;
            stepContext = null;
            jobExecution = null;
        } else {
            stepContext = stepExecution.getExecutionContext() != null
                    ? stepExecution.getExecutionContext()
                    : new ExecutionContext();
            jobExecution = stepExecution.getJobExecution();
        }

        final String jobId = jobExecution != null ? String.valueOf(jobExecution.getId()) : "unknown";
        final ExecutionContext jobContext = jobExecution != null ? jobExecution.getExecutionContext() : null;
        final String userIdForExternalCalls = jobContext != null ? jobContext.getString(USERID_FOR_EXTERNAL_CALLS, null) : null;
        if (proceed && (userIdForExternalCalls == null || userIdForExternalCalls.isBlank())) {
            log.warn("jobId={} Missing user id for external calls (key={}); skipping.", jobId, USERID_FOR_EXTERNAL_CALLS);
            proceed = false;
        }

        final UUID materialId;
        final UUID caseId;
        final UUID documentId;
        final String materialName;
        if (proceed) {
            materialId = readUuidFromContext(stepContext, CTX_MATERIAL_ID_KEY, "materialId", jobId);
            caseId = readUuidFromContext(stepContext, CTX_CASE_ID_KEY, "caseId", jobId);
            documentId = readUuidFromContext(stepContext, CTX_DOC_ID_KEY, "docId", jobId);
            materialName = stepContext.containsKey(CTX_MATERIAL_NAME) ? stepContext.getString(CTX_MATERIAL_NAME) : "";
            if (materialId == null || caseId == null || documentId == null) {
                proceed = false;
            }
        } else {
            materialId = null;
            caseId = null;
            documentId = null;
            materialName = "";
        }

        if (proceed && !getBooleanFlag(stepContext, CTX_MATERIAL_NEW_UPLOAD)) {
            log.info("jobId={} Skipping upload: existing docId={} will be reused for caseId={}, materialId={}.",
                    jobId, documentId, caseId, materialId);
            proceed = false;
        }

        if (proceed) {
            final String downloadUrl = fetchDownloadUrlWithRetry(jobId, materialId, userIdForExternalCalls);
            if (downloadUrl == null) {
                log.warn("jobId={} No download URL for materialId={}; skipping.", jobId, materialId);
            } else {
                final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern(uploadProperties.datePattern());
                final String today = utcNow().format(dateFmt);
                final String blobName = documentId + "_" + today + uploadProperties.fileExtension();
                final String destinationPath = blobName;
                final String contentType = uploadProperties.contentType();

                final Map<String, String> metadata =
                        createBlobMetadata(documentId, materialId, caseId.toString(), today, materialName);

                final String blobUrl = copyToBlobWithRetry(jobId, downloadUrl, destinationPath, contentType, metadata, materialId);
                if (blobUrl == null) {
                    log.warn("jobId={} Blob copy returned null URL; destinationPath={}, materialId={}, contentType={}. Skipping persist.",
                            jobId, destinationPath, materialId, contentType);
                } else {
                    final long sizeBytes = getBlobSizeWithRetry(jobId, destinationPath);

                    final CaseDocument entity = new CaseDocument();
                    entity.setDocId(documentId);
                    entity.setCaseId(caseId);
                    entity.setMaterialId(materialId);
                    entity.setDocName(blobName);
                    entity.setBlobUri(blobUrl);
                    entity.setContentType(contentType);
                    entity.setSizeBytes(sizeBytes);
                    entity.setUploadedAt(utcNow());
                    entity.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
                    entity.setIngestionPhaseAt(utcNow());

                    caseDocumentRepository.saveAndFlush(entity);

                    log.info("jobId={} Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}",
                            jobId, documentId, caseId, materialId, sizeBytes, blobUrl);
                }
            }
        }

        return RepeatStatus.FINISHED;
    }

    private String fetchDownloadUrlWithRetry(final String jobId,
                                             final UUID materialId,
                                             final String userIdForExternalCalls) {
        return retryTemplate.execute((RetryContext retryContext) -> {
            final int attempt = retryContext.getRetryCount() + 1;
            if (attempt >= MIN_RETRY_LOG_ATTEMPT) {
                log.warn("jobId={} Retrying getMaterialDownloadUrl attempt #{} materialId={}, userId={}",
                        jobId, attempt, materialId, userIdForExternalCalls);
            }
            final Optional<String> urlOpt = progressionClient.getMaterialDownloadUrl(materialId, userIdForExternalCalls);
            if (urlOpt.isEmpty() || urlOpt.get().isBlank()) {
                throw new IllegalStateException("Empty download URL from Progression");
            }
            return urlOpt.get();
        }, (RetryContext recovery) -> {
            final Throwable last = recovery.getLastThrowable();
            final String cause = last != null ? last.getMessage() : "n/a";
            log.error("jobId={} getMaterialDownloadUrl failed after {} attempts for materialId={}, userId={}. cause={}",
                    jobId, recovery.getRetryCount(), materialId, userIdForExternalCalls, cause, last);
            return null;
        });
    }

    private String copyToBlobWithRetry(final String jobId,
                                       final String downloadUrl,
                                       final String destinationPath,
                                       final String contentType,
                                       final Map<String, String> metadata,
                                       final UUID materialId) {
        return retryTemplate.execute((RetryContext retryContext) -> {
            final int attempt = retryContext.getRetryCount() + 1;
            if (attempt >= MIN_RETRY_LOG_ATTEMPT) {
                log.warn("jobId={} Retrying copyFromUrl attempt #{} path={}, materialId={}",
                        jobId, attempt, destinationPath, materialId);
            }
            return storageService.copyFromUrl(downloadUrl, destinationPath, contentType, metadata);
        }, (RetryContext recovery) -> {
            final Throwable last = recovery.getLastThrowable();
            final String cause = last != null ? last.getMessage() : "n/a";
            log.error("jobId={} copyFromUrl failed after {} attempts. materialId={}, path={}, cause={}",
                    jobId, recovery.getRetryCount(), materialId, destinationPath, cause, last);
            return null;
        });
    }

    private long getBlobSizeWithRetry(final String jobId, final String destinationPath) {
        return retryTemplate.execute((RetryContext retryContext) -> {
            final int attempt = retryContext.getRetryCount() + 1;
            if (attempt >= MIN_RETRY_LOG_ATTEMPT) {
                log.warn("jobId={} Retrying getBlobSize attempt #{} path={}", jobId, attempt, destinationPath);
            }
            return storageService.getBlobSize(destinationPath);
        }, (RetryContext recovery) -> {
            final Throwable last = recovery.getLastThrowable();
            final String cause = last != null ? last.getMessage() : "n/a";
            log.warn("jobId={} getBlobSize failed after {} attempts. path={}, cause={}",
                    jobId, recovery.getRetryCount(), destinationPath, cause, last);
            return UNKNOWN_SIZE_BYTES;
        });
    }

    private static boolean getBooleanFlag(final ExecutionContext context, final String key) {
        boolean flag = false;
        final Object value = context.get(key);
        if (value instanceof Boolean boolValue) {
            flag = boolValue;
        } else if (value instanceof String stringValue) {
            flag = Boolean.parseBoolean(stringValue);
        }
        return flag;
    }

    private UUID readUuidFromContext(final ExecutionContext context,
                                     final String key,
                                     final String label,
                                     final String jobId) {
        final String raw = context != null && context.containsKey(key) ? context.getString(key) : null;
        final UUID parsed = parseUuidOrNull(raw);
        if (raw == null) {
            log.warn("jobId={} Missing required key '{}' in step context.", jobId, key);
        } else if (parsed == null) {
            log.warn("jobId={} Invalid UUID for {} (key='{}'): '{}'", jobId, label, key, raw);
        }
        return parsed;
    }

    private Map<String, String> createBlobMetadata(final UUID newDocumentId,
                                                   final UUID materialId,
                                                   final String caseId,
                                                   final String uploadedDate,
                                                   final String materialName) {
        try {
            final Map<String, Object> metadataJson = Map.of(
                    META_CASE_ID, caseId,
                    META_MATERIAL_ID, materialId.toString(),
                    META_MATERIAL_NAME, materialName,
                    META_UPLOADED_AT, uploadedDate
            );
            return Map.of(
                    META_DOCUMENT_ID, newDocumentId.toString(),
                    META_METADATA, objectMapper.writeValueAsString(metadataJson)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create blob metadata", exception);
        }
    }
}
