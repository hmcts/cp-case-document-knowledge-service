package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

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

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        @SuppressWarnings("unchecked") final Map<String, String> materialToCaseMap =
                (Map<String, String>) contribution.getStepExecution()
                        .getJobExecution()
                        .getExecutionContext()
                        .get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY);

        if (materialToCaseMap == null || materialToCaseMap.isEmpty()) {
            log.info("No material-to-case mapping found; skipping upload.");
        } else {
            for (final Map.Entry<String, String> entry : materialToCaseMap.entrySet()) {
                final String materialIdStr = entry.getKey();
                final String caseIdStr = entry.getValue();

                if (materialIdStr == null || caseIdStr == null) {
                    continue;
                }

                final UUID materialID;
                try {
                    materialID = UUID.fromString(materialIdStr);
                } catch (final IllegalArgumentException e) {
                    log.warn("Invalid materialId '{}'; skipping.", materialIdStr);
                    continue;
                }

                final Optional<String> downloadUrl = progressionClient.getMaterialDownloadUrl(materialID);
                if (downloadUrl.isEmpty()) {
                    log.warn("No download URL for materialId {}; skipping.", materialID);
                    continue;
                }

                final UUID documentId = UUID.randomUUID();
                final String uploadDate = utcNow().format(ofPattern("yyyyMMdd"));
                final String blobName = String.format("%s_%s.pdf", materialID, uploadDate);
                final String destBlobPath = String.format("cases/%s/%s", uploadDate, blobName);
                final String contentType = "application/pdf";

                final Map<String, String> metadata =
                        createBlobMetadata(documentId, materialID, caseIdStr, uploadDate);

                final String blobUrl =
                        storageService.copyFromUrl(downloadUrl.get(), destBlobPath, contentType, metadata);

                final long sizeBytes = storageService.getBlobSize(destBlobPath);

                final CaseDocument caseDocument = buildDoc(
                        documentId,
                        UUID.fromString(caseIdStr),
                        blobName,
                        blobUrl,
                        contentType,
                        sizeBytes
                );

                caseDocumentRepository.save(caseDocument);
            }
        }

        return RepeatStatus.FINISHED; // single exit point
    }
}