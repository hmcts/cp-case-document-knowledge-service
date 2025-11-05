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

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;       // persisted doc_id (for downstream steps)
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_MATERIAL_ID_KEY;  // material id provided by partitioner
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.USERID_FOR_EXTERNAL_CALLS;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

@Component
@RequiredArgsConstructor
@Slf4j
public class UploadAndPersistTasklet implements Tasklet {

    private static final String META_CASE_ID = "caseId";
    private static final String META_MATERIAL_ID = "materialId";
    private static final String META_UPLOADED_AT = "uploadedAt";
    private static final String META_DOCUMENT_ID = "documentId";
    private static final String META_METADATA = "metadata";

    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final CaseDocumentRepository caseDocumentRepository;
    private final UploadProperties uploadProperties;

    @Override
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.OnlyOneReturn","ignoreElseIf"})
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final RepeatStatus status = RepeatStatus.FINISHED;
        boolean proceed = true;

        final StepExecution stepExecution = (contribution != null) ? contribution.getStepExecution() : null;
        if (stepExecution == null) {
            log.warn("StepExecution is null; finishing with no work.");
            proceed = false;
        }

        final ExecutionContext jobCtx;
        ExecutionContext stepCtx = null;
        String userId = null;

        if (proceed) {
            final JobExecution jobExecution = stepExecution.getJobExecution();
            jobCtx = (jobExecution != null) ? jobExecution.getExecutionContext() : new ExecutionContext();
            stepCtx = (stepExecution.getExecutionContext() != null)
                    ? stepExecution.getExecutionContext()
                    : new ExecutionContext();

            userId = (String) jobCtx.get(USERID_FOR_EXTERNAL_CALLS);
            if (userId == null || userId.isBlank()) {
                log.warn("User id for external calls (key={}) is missing; no uploads will be attempted.", USERID_FOR_EXTERNAL_CALLS);
                proceed = false;
            }
        }

        UUID materialId = null;
        UUID caseId = null;
        UUID persistedDocId = null;
        if (proceed) {
            final boolean hasMaterialId = stepCtx.containsKey(CTX_MATERIAL_ID_KEY);
            final boolean hasCaseId = stepCtx.containsKey(CTX_CASE_ID_KEY);

            if (!hasMaterialId || !hasCaseId) {// NOPMD - needed to handle no rows in map
                log.warn("Partition context missing required keys: {} present?={}, {} present?={}",
                        CTX_MATERIAL_ID_KEY, hasMaterialId, CTX_CASE_ID_KEY, hasCaseId);
                proceed = false;
            } else {
                final String materialIdStr = stepCtx.getString(CTX_MATERIAL_ID_KEY);
                final String caseIdStr = stepCtx.getString(CTX_CASE_ID_KEY);
                final String persistedDocIdStr = stepCtx.getString(CTX_DOC_ID_KEY);
                try {
                    materialId = UUID.fromString(materialIdStr);
                    caseId = UUID.fromString(caseIdStr);
                    persistedDocId = UUID.fromString(persistedDocIdStr);
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid UUID(s) in partition context. materialId='{}', caseId='{}' — skipping", materialIdStr, caseIdStr);
                    proceed = false;
                }
            }
        }

        String downloadUrl = null;
        if (proceed) {
            final Optional<String> downloadUrlOpt = progressionClient.getMaterialDownloadUrl(materialId, userId);
            if (downloadUrlOpt.isEmpty()) {
                log.warn("No download URL for materialId={} — skipping.", materialId);
                proceed = false;
            } else {
                downloadUrl = downloadUrlOpt.get();
            }
        }

        String destBlobPath = null;
        String contentType = null;
        String blobUrl = null;
        String blobName = null;

        if (proceed) {
            final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern(uploadProperties.datePattern());
            final String today = utcNow().format(dateFmt);

            blobName = persistedDocId + "_" + today + uploadProperties.fileExtension();
            // If you prefer prefix + date folders, use:
            // destBlobPath = uploadProperties.blobPrefix() + "/" + today + "/" + blobName;
            // Current behaviour keeps just the name:
            destBlobPath = blobName;

            contentType = uploadProperties.contentType();

            final Map<String, String> metadata =
                    createBlobMetadata(persistedDocId, materialId, caseId.toString(), today);

            try {
                blobUrl = storageService.copyFromUrl(downloadUrl, destBlobPath, contentType, metadata);
            } catch (Exception e) {
                log.error("Failed to copy material to blob storage. materialId={}, path={}", materialId, destBlobPath, e);
                proceed = false;
            }
        }

        if (proceed) {
            long sizeBytes;
            try {
                sizeBytes = storageService.getBlobSize(destBlobPath);
            } catch (Exception e) {
                log.error("Failed to obtain blob size. path={}", destBlobPath, e);
                sizeBytes = -1L;
            }

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
        }

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
