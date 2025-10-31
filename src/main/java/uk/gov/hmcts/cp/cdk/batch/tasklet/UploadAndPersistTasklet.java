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
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.time.format.DateTimeFormatter.ofPattern;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

@Component
@RequiredArgsConstructor
@Slf4j
public class UploadAndPersistTasklet implements Tasklet {

    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;

    @SuppressWarnings("unused")
    private final PlatformTransactionManager transactionManager;

    private final CaseDocumentRepository caseDocumentRepository;

    private static CaseDocument buildDoc(final UUID docId,
                                         final UUID caseId,
                                         final String docName,
                                         final String blobUrl,
                                         final String contentType,
                                         final long size) {
        final CaseDocument caseDocument = new CaseDocument();
        caseDocument.setDocId(docId);
        caseDocument.setCaseId(caseId);
        caseDocument.setDocName(docName);
        caseDocument.setBlobUri(blobUrl);
        caseDocument.setContentType(contentType);
        caseDocument.setSizeBytes(size);
        caseDocument.setUploadedAt(utcNow());
        caseDocument.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
        caseDocument.setIngestionPhaseAt(utcNow());
        return caseDocument;
    }

    private Map<String, String> createBlobMetadata(final UUID documentId,
                                                   final UUID materialId,
                                                   final String caseId,
                                                   final String uploadedDate) {
        try {
            final Map<String, Object> metadataJson = Map.of(
                    "case_id", caseId,
                    "material_id", materialId.toString(),
                    "uploaded_at", uploadedDate
            );

            return Map.of(
                    "document_id", documentId.toString(),
                    "metadata", objectMapper.writeValueAsString(metadataJson)
            );
        } catch (final Exception e) {
            throw new RuntimeException("Failed to create blob metadata", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> resolveMaterialToCaseMap(final StepExecution stepExecution) {
        if (stepExecution == null) {
            log.info("No StepExecution available; nothing to do.");
            return Collections.emptyMap();
        }

        final ExecutionContext stepCtx =
                Optional.ofNullable(stepExecution.getExecutionContext()).orElseGet(ExecutionContext::new);

        final JobExecution jobExec = stepExecution.getJobExecution();
        final ExecutionContext jobCtx =
                Optional.ofNullable(jobExec).map(JobExecution::getExecutionContext).orElseGet(ExecutionContext::new);

        final Object val = Optional.ofNullable(stepCtx.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY))
                .orElse(jobCtx.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY));

        if (val == null) {
            log.info(
                    "No material-to-case mapping found; step size={}, job size={}, stepHasKey={}, jobHasKey={}",
                    stepCtx.size(),
                    jobCtx.size(),
                    stepCtx.containsKey(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY),
                    jobCtx.containsKey(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)
            );
            return Collections.emptyMap();
        }
        if (!(val instanceof Map)) {
            log.warn("Material-to-case mapping present but not a Map: type={}", val.getClass());
            return Collections.emptyMap();
        }
        return (Map<String, String>) val;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final StepExecution stepExecution = contribution != null ? contribution.getStepExecution() : null;
        final Map<String, String> materialToCaseMap = resolveMaterialToCaseMap(stepExecution);

        if (materialToCaseMap.isEmpty()) {
            return RepeatStatus.FINISHED;
        }

        for (final Map.Entry<String, String> entry : materialToCaseMap.entrySet()) {
            final String materialIdStr = entry.getKey();
            final String caseIdStr = entry.getValue();

            if (materialIdStr == null || caseIdStr == null) {
                log.warn("Null key/value in materialToCaseMap: key={}, value={}", materialIdStr, caseIdStr);
                continue;
            }

            final UUID materialId;
            final UUID caseId;
            try {
                materialId = UUID.fromString(materialIdStr);
                caseId = UUID.fromString(caseIdStr);
            } catch (final IllegalArgumentException e) {
                log.warn("Invalid UUID(s). materialId='{}', caseId='{}' — skipping", materialIdStr, caseIdStr);
                continue;
            }

            final Optional<String> downloadUrl = progressionClient.getMaterialDownloadUrl(materialId);
            if (downloadUrl.isEmpty()) {
                log.warn("No download URL for materialId={}; skipping.", materialId);
                continue;
            }

            final UUID documentId = UUID.randomUUID();
            final String uploadDate = utcNow().format(ofPattern("yyyyMMdd"));
            final String blobName = materialId + "_" + uploadDate + ".pdf";
            final String destBlobPath = "cases/" + uploadDate + "/" + blobName;
            final String contentType = "application/pdf";

            final Map<String, String> metadata =
                    createBlobMetadata(documentId, materialId, caseId.toString(), uploadDate);

            final String blobUrl = storageService.copyFromUrl(
                    downloadUrl.get(), destBlobPath, contentType, metadata
            );

            final long sizeBytes = storageService.getBlobSize(blobUrl);

            final CaseDocument caseDocument = buildDoc(
                    documentId, caseId, blobName, blobUrl, contentType, sizeBytes
            );

            caseDocumentRepository.saveAndFlush(caseDocument);
            log.info("Saved CaseDocument: docId={}, caseId={}, sizeBytes={}", documentId, caseId, sizeBytes);
        }
        return RepeatStatus.FINISHED;
    }
}
