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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.*;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

@Component
@RequiredArgsConstructor
@Slf4j
public class UploadAndPersistTasklet implements Tasklet {

    private static final String META_CASE_ID = "case_id";
    private static final String META_MATERIAL_ID = "material_id";
    private static final String META_UPLOADED_AT = "uploaded_at";
    private static final String META_DOCUMENT_ID = "document_id";
    private static final String META_METADATA = "metadata";

    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final CaseDocumentRepository caseDocumentRepository;
    private final UploadProperties uploadProperties;

    private final RetryTemplate retryTemplate;

    @Override
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.OnlyOneReturn","ignoreElseIf"})
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final RepeatStatus status = RepeatStatus.FINISHED;

        final StepExecution stepExecution = (contribution != null) ? contribution.getStepExecution() : null;
        if (stepExecution == null) {
            log.warn("StepExecution is null; finishing with no work.");
            return status;
        }

        final ExecutionContext stepCtx = (stepExecution.getExecutionContext() != null)
                ? stepExecution.getExecutionContext()
                : new ExecutionContext();

        final JobExecution jobExecution = stepExecution.getJobExecution();
        final String userId = (jobExecution != null && jobExecution.getExecutionContext() != null)
                ? jobExecution.getExecutionContext().getString(USERID_FOR_EXTERNAL_CALLS, null)
                : null;

        if (userId == null || userId.isBlank()) {
            log.warn("User id for external calls (key={}) is missing; no uploads will be attempted.", USERID_FOR_EXTERNAL_CALLS);
            return status;
        }

        final boolean hasMaterialId = stepCtx.containsKey(CTX_MATERIAL_ID_KEY);
        final boolean hasCaseId = stepCtx.containsKey(CTX_CASE_ID_KEY);

        if (!hasMaterialId || !hasCaseId) {
            log.warn("Partition context missing required keys: {} present?={}, {} present?={}",
                    CTX_MATERIAL_ID_KEY, hasMaterialId, CTX_CASE_ID_KEY, hasCaseId);
            return status;
        }

        final UUID materialId;
        final UUID caseId;
        final UUID persistedDocId;
        try {
            final String materialIdStr = stepCtx.getString(CTX_MATERIAL_ID_KEY);
            final String caseIdStr = stepCtx.getString(CTX_CASE_ID_KEY);
            final String persistedDocIdStr = stepCtx.getString(CTX_DOC_ID_KEY);
            materialId = UUID.fromString(materialIdStr);
            caseId = UUID.fromString(caseIdStr);
            persistedDocId = UUID.fromString(persistedDocIdStr);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid UUID(s) in partition context. materialId='{}', caseId='{}' — skipping",
                    stepCtx.getString(CTX_MATERIAL_ID_KEY), stepCtx.getString(CTX_CASE_ID_KEY));
            return status;
        }

        final UUID fMaterialId = materialId;
        final String fUserId = userId;

        final String downloadUrl = retryTemplate.execute((RetryContext ctx) -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("Retrying getMaterialDownloadUrl (attempt #{}) materialId={}, userId={}",
                        ctx.getRetryCount() + 1, fMaterialId, fUserId);
            }
            final Optional<String> opt = progressionClient.getMaterialDownloadUrl(fMaterialId, fUserId);
            if (opt.isEmpty() || opt.get().isBlank()) {
                throw new IllegalStateException("Empty download URL from Progression");
            }
            return opt.get();
        }, (RetryContext ctx) -> {
            log.warn("getMaterialDownloadUrl permanently failed after {} attempts for materialId={}, userId={}",
                    ctx.getRetryCount(), fMaterialId, fUserId, ctx.getLastThrowable());
            return null;
        });

        if (downloadUrl == null) {
            log.warn("No download URL for materialId={} — skipping.", materialId);
            return status;
        }

        final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern(uploadProperties.datePattern());
        final String today = utcNow().format(dateFmt);

        final String blobName = persistedDocId + "_" + today + uploadProperties.fileExtension();
        final String destBlobPath = blobName;
        final String contentType = uploadProperties.contentType();

        final Map<String, String> metadata =
                createBlobMetadata(persistedDocId, materialId, caseId.toString(), today);

        final String fDestBlobPath = destBlobPath;
        final String fContentType = contentType;
        final Map<String, String> fMetadata = metadata;
        final String fDownloadUrl = downloadUrl;

        final String blobUrl = retryTemplate.execute((RetryContext ctx) -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("Retrying copyFromUrl (attempt #{}) path={}, materialId={}",
                        ctx.getRetryCount() + 1, fDestBlobPath, fMaterialId);
            }
            return storageService.copyFromUrl(fDownloadUrl, fDestBlobPath, fContentType, fMetadata);
        }, (RetryContext ctx) -> {
            log.error("copyFromUrl permanently failed after {} attempts. materialId={}, path={}",
                    ctx.getRetryCount(), fMaterialId, fDestBlobPath, ctx.getLastThrowable());
            return null;
        });

        if (blobUrl == null) {
            return status;
        }

        final Long sizeObj = retryTemplate.execute((RetryContext ctx) -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("Retrying getBlobSize (attempt #{}) path={}",
                        ctx.getRetryCount() + 1, fDestBlobPath);
            }
            return storageService.getBlobSize(fDestBlobPath);
        }, (RetryContext ctx) -> {
            log.warn("getBlobSize permanently failed after {} attempts. path={}",
                    ctx.getRetryCount(), fDestBlobPath, ctx.getLastThrowable());
            return null;
        });
        final long sizeBytes = (sizeObj != null) ? sizeObj : -1L;

        final CaseDocument caseDocumentEntity = new CaseDocument();
        caseDocumentEntity.setDocId(persistedDocId);
        caseDocumentEntity.setCaseId(caseId);
        caseDocumentEntity.setMaterialId(materialId);
        caseDocumentEntity.setDocName(blobName);
        caseDocumentEntity.setBlobUri(blobUrl);
        caseDocumentEntity.setContentType(contentType);
        caseDocumentEntity.setSizeBytes(sizeBytes);
        caseDocumentEntity.setUploadedAt(utcNow());
        caseDocumentEntity.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
        caseDocumentEntity.setIngestionPhaseAt(utcNow());
        caseDocumentRepository.saveAndFlush(caseDocumentEntity);

        log.info("Saved CaseDocument: docId={}, caseId={}, sizeBytes={}", persistedDocId, caseId, sizeBytes);

        return status;
    }

    private Map<String, String> createBlobMetadata(final UUID newDocumentId,
                                                   final UUID materialId,
                                                   final String caseId,
                                                   final String uploadedDate) {
        try {
            final Map<String, Object> metadataJson = Map.of(
                    META_CASE_ID, caseId,
                    META_MATERIAL_ID, materialId.toString(),
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
