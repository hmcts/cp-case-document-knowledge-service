package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static java.time.format.DateTimeFormatter.ofPattern;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class UploadAndPersistTasklet implements Tasklet {
    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final PlatformTransactionManager transactionManager;
    private final CaseDocumentRepository caseDocumentRepository;

    private static CaseDocument buildDoc(UUID docId, UUID caseId, String docName,
                                         String blobUrl,
                                         String contentType, long size) {
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

    private Map<String, String> createBlobMetadata(
            final UUID documentId,
            final UUID materialId,
            final String caseId,
            final String uploadedDate
    ) {
        try {
            final Map<String, Object> metadataJson = Map.of(
                    "case_id", caseId,
                    "material_id", materialId.toString(),// need to check with satish
                    "uploaded_at", uploadedDate
            );
            log.info("Creating blob metadata for documentId={}, materialId={}, caseId={}, uploadedDate={}",
                    documentId, materialId, caseId, uploadedDate);

            return Map.of(
                    "document_id", documentId.toString(),
                    "metadata", objectMapper.writeValueAsString(metadataJson)
            );
        } catch (Exception e) {
            log.error("Failed to create blob metadata for documentId={}, materialId={}, caseId={}",
                    documentId, materialId, caseId, e);
        }
        return Map.of();
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();

        @SuppressWarnings("unchecked") final Map<String, String> materialToCaseMap =
                (Map<String, String>) contribution.getStepExecution()
                        .getJobExecution()
                        .getExecutionContext()
                        .get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP);

        if (materialToCaseMap == null || materialToCaseMap.isEmpty()) {
            log.warn("No material-to-case mapping found, skipping upload");
            return RepeatStatus.FINISHED;
        }

        for (Map.Entry<String, String> entry : materialToCaseMap.entrySet()) {
            final String materialIdStr = entry.getKey();
            final String caseIdStr = entry.getValue();

            if (materialIdStr == null || caseIdStr == null) {
                log.warn("Skipping entry with null materialId or caseId: materialId={}, caseId={}", materialIdStr, caseIdStr);
                continue;
            }

            final UUID materialID;
            try {
                materialID = UUID.fromString(materialIdStr);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid materialId UUID: {}, skipping this entry", materialIdStr);
                continue;
            }

            final Optional<String> downloadUrl = progressionClient.getMaterialDownloadUrl(materialID);

            if (downloadUrl.isEmpty()) {
                log.warn("No download URL found for materialId={}, skipping upload", materialID);
                continue;
            }

            final UUID documentId = UUID.randomUUID();
            final String uploadDate = utcNow().format(ofPattern("yyyyMMdd"));
            final String blobName = String.format("%s_%s.pdf", materialID, uploadDate);
            final String destBlobPath = String.format("cases/%s/%s", uploadDate, blobName);
            final String contentType = "application/pdf";
            Map<String, String> metadata;
            try {
                metadata = createBlobMetadata(documentId, materialID, caseIdStr, uploadDate);
            } catch (RuntimeException e) {
                log.error("Error creating metadata for documentId={}, materialId={}, caseId={}. Skipping this document.", documentId, materialID, caseIdStr);
                continue;
            }
            log.info("Copying blob from URL to destination path: {}", destBlobPath);
            final String blobUrl = storageService.copyFromUrl(downloadUrl.get(), destBlobPath, contentType, metadata);
            final long sizeBytes = storageService.getBlobSize(destBlobPath);

            final CaseDocument caseDocument =
                    buildDoc(documentId, UUID.fromString(caseIdStr),
                            blobName, blobUrl, contentType, sizeBytes);
            caseDocumentRepository.save(caseDocument);
            log.info("Uploaded and persisted document with documentId={}, caseId={}, blobUrl={}", documentId, caseIdStr, blobUrl);
        }
        log.info("Completed UploadAndPersistTasklet execution");
        return RepeatStatus.FINISHED;

    }
}
