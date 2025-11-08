package uk.gov.hmcts.cp.cdk.batch.tasklet;

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
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.UploadProperties;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.time.format.DateTimeFormatter;
import java.util.*;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.*;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

@Component
@RequiredArgsConstructor
@Slf4j
public class UploadAndPersistTasklet implements Tasklet {

    /**
     * Idempotency rule: if we already have a document for (caseId, materialId) in any of these phases,
     * we skip re-copying from Progression to avoid duplicate uploads.
     */
    private static final Set<DocumentIngestionPhase> SKIP_IF_EXISTS_PHASES = EnumSet.of(
            DocumentIngestionPhase.UPLOADED,
            DocumentIngestionPhase.INGESTED,
            DocumentIngestionPhase.INGESTING
    );

    // Blob metadata keys
    private static final String META_CASE_ID = "case_id";
    private static final String META_MATERIAL_ID = "material_id";
    private static final String META_MATERIAL_NAME = "material_name";
    private static final String META_UPLOADED_AT = "uploaded_at";
    private static final String META_DOCUMENT_ID = "document_id";
    private static final String META_METADATA = "metadata";

    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final CaseDocumentRepository caseDocumentRepository;
    private final UploadProperties uploadProperties;
    private final RetryTemplate retryTemplate;

    /**
     * Uploads a material from Progression to blob storage and persists a CaseDocument row.
     * Idempotent by (docId) and by (caseId, materialId) in {UPLOADED, INGESTED, INGESTING}.
     */
    @Override
    @SuppressWarnings("PMD.OnlyOneReturn")
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final StepExecution stepExecution = contribution != null ? contribution.getStepExecution() : null;
        if (stepExecution == null) {
            log.warn("UploadAndPersistTasklet invoked without StepExecution; nothing to do.");
            return RepeatStatus.FINISHED;
        }

        final ExecutionContext stepCtx = stepExecution.getExecutionContext() != null
                ? stepExecution.getExecutionContext()
                : new ExecutionContext();

        final JobExecution jobExecution = stepExecution.getJobExecution();
        final String jobId = jobExecution != null ? String.valueOf(jobExecution.getId()) : "unknown";
        final String userId = (jobExecution != null && jobExecution.getExecutionContext() != null)
                ? jobExecution.getExecutionContext().getString(USERID_FOR_EXTERNAL_CALLS, null)
                : null;

        if (userId == null || userId.isBlank()) {
            log.warn("jobId={} Missing user id for external calls (key={}); skipping.", jobId, USERID_FOR_EXTERNAL_CALLS);
            return RepeatStatus.FINISHED;
        }

        final UUID materialId = parseUuid(stepCtx, CTX_MATERIAL_ID_KEY, "materialId", jobId);
        final UUID caseId = parseUuid(stepCtx, CTX_CASE_ID_KEY, "caseId", jobId);
        final UUID docId = parseUuid(stepCtx, CTX_DOC_ID_KEY, "docId", jobId);
        final String materialName = stepCtx.containsKey(CTX_MATERIAL_NAME) ? stepCtx.getString(CTX_MATERIAL_NAME) : "";

        if (materialId == null || caseId == null || docId == null) {
            return RepeatStatus.FINISHED;
        }


        // 1) If this docId is already persisted, do NOT repeat work.
        if (caseDocumentRepository.existsByDocId(docId)) {
            log.info("jobId={} Idempotent skip: docId={} already persisted.", jobId, docId);
            return RepeatStatus.FINISHED;
        }

        // 2) If we already have a successful/ongoing upload for (caseId, materialId), skip.
        if (caseDocumentRepository.existsByCaseIdAndMaterialIdAndIngestionPhaseIn(caseId, materialId, SKIP_IF_EXISTS_PHASES)) {
            log.info("jobId={} Idempotent skip: caseId={}, materialId={} already in phases {}.", jobId, caseId, materialId, SKIP_IF_EXISTS_PHASES);
            return RepeatStatus.FINISHED;
        }

        // ---- FETCH DOWNLOAD URL FROM PROGRESSION ----------------------------------------------
        final String downloadUrl = retryTemplate.execute((RetryContext ctx) -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("jobId={} Retrying getMaterialDownloadUrl attempt #{} materialId={}, userId={}", jobId, ctx.getRetryCount() + 1, materialId, userId);
            }
            final Optional<String> opt = progressionClient.getMaterialDownloadUrl(materialId, userId);
            if (opt.isEmpty() || opt.get().isBlank()) {
                throw new IllegalStateException("Empty download URL from Progression");
            }
            return opt.get();
        }, (RetryContext ctx) -> {
            log.error("jobId={} getMaterialDownloadUrl failed after {} attempts for materialId={}, userId={}. cause={}",
                    jobId, ctx.getRetryCount(), materialId, userId, ctx.getLastThrowable() != null ? ctx.getLastThrowable().getMessage() : "n/a", ctx.getLastThrowable());
            return null;
        });

        if (downloadUrl == null) {
            log.warn("jobId={} No download URL for materialId={}; skipping.", jobId, materialId);
            return RepeatStatus.FINISHED;
        }

        // ---- COPY TO BLOB STORAGE --------------------------------------------------------------
        final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern(uploadProperties.datePattern());
        final String today = utcNow().format(dateFmt);
        final String blobName = docId + "_" + today + uploadProperties.fileExtension();
        final String destBlobPath = blobName;
        final String contentType = uploadProperties.contentType();

        final Map<String, String> metadata = createBlobMetadata(docId, materialId, caseId.toString(), today, materialName);

        final String blobUrl = retryTemplate.execute((RetryContext ctx) -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("jobId={} Retrying copyFromUrl attempt #{} path={}, materialId={}", jobId, ctx.getRetryCount() + 1, destBlobPath, materialId);
            }
            return storageService.copyFromUrl(downloadUrl, destBlobPath, contentType, metadata);
        }, (RetryContext ctx) -> {
            log.error("jobId={} copyFromUrl failed after {} attempts. materialId={}, path={}, cause={}",
                    jobId, ctx.getRetryCount(), materialId, destBlobPath, ctx.getLastThrowable() != null ? ctx.getLastThrowable().getMessage() : "n/a", ctx.getLastThrowable());
            return null;
        });

        if (blobUrl == null) {
            return RepeatStatus.FINISHED;
        }

        // ---- LOOKUP SIZE & PERSIST ROW ---------------------------------------------------------
        final Long sizeObj = retryTemplate.execute((RetryContext ctx) -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("jobId={} Retrying getBlobSize attempt #{} path={}", jobId, ctx.getRetryCount() + 1, destBlobPath);
            }
            return storageService.getBlobSize(destBlobPath);
        }, (RetryContext ctx) -> {
            log.warn("jobId={} getBlobSize failed after {} attempts. path={}, cause={}", jobId, ctx.getRetryCount(), destBlobPath,
                    ctx.getLastThrowable() != null ? ctx.getLastThrowable().getMessage() : "n/a", ctx.getLastThrowable());
            return null;
        });
        final long sizeBytes = sizeObj != null ? sizeObj : -1L;

        final CaseDocument entity = new CaseDocument();
        entity.setDocId(docId);
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
                jobId, docId, caseId, materialId, sizeBytes, blobUrl);

        stepCtx.put("uploaded_blob_path", destBlobPath);
        stepCtx.put("uploaded_blob_url", blobUrl);
        stepCtx.put("uploaded_size_bytes", sizeBytes);

        return RepeatStatus.FINISHED;
    }

    @SuppressWarnings("PMD.OnlyOneReturn")
    private UUID parseUuid(final ExecutionContext ctx, final String key, final String label, final String jobId) {
        if (!ctx.containsKey(key)) {
            log.warn("jobId={} Missing required key '{}' in step context.", jobId, key);
            return null;
        }
        try {
            return UUID.fromString(ctx.getString(key));
        } catch (IllegalArgumentException ex) {
            log.warn("jobId={} Invalid UUID for {} (key='{}'): '{}'", jobId, label, key, ctx.getString(key));
            return null;
        }
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
        } catch (final Exception e) {
            throw new RuntimeException("Failed to create blob metadata", e);
        }
    }
}
