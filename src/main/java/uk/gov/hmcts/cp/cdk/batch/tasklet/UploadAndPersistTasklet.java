package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.time.format.DateTimeFormatter.ofPattern;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;


@Component
@RequiredArgsConstructor
public class UploadAndPersistTasklet implements Tasklet {
    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final PlatformTransactionManager transactionManager;
    private final CaseDocumentRepository caseDocumentRepository;

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

            return Map.of(
                    "document_id", documentId.toString(),
                    "metadata", objectMapper.writeValueAsString(metadataJson)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to create blob metadata", e);
        }
    }

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

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();

        @SuppressWarnings("unchecked") final Map<String, String> materialToCaseMap =
                (Map<String, String>) contribution.getStepExecution()
                        .getJobExecution()
                        .getExecutionContext()
                        .get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP);

        if (materialToCaseMap == null || materialToCaseMap.isEmpty()) {
            System.out.println("No material-to-case mapping found, skipping upload");//No PMD
            return RepeatStatus.FINISHED;
        }

        for (Map.Entry<String, String> entry : materialToCaseMap.entrySet()) {
            final String materialIdStr = entry.getKey();
            final String caseIdStr = entry.getValue();

            if (materialIdStr == null || caseIdStr == null) {
               continue;
            }

            final UUID materialID;
            try {
                materialID = UUID.fromString(materialIdStr);
            } catch (IllegalArgumentException e) {
               continue;
            }

            final Optional<String> downloadUrl = progressionClient.getMaterialDownloadUrl(materialID);

            if (downloadUrl.isEmpty()) {
                continue;
            }

            final UUID documentId = UUID.randomUUID();

            final String uploadDate = utcNow().format(ofPattern("yyyyMMdd"));
            final String blobName = String.format("%s_%s.pdf", materialID, uploadDate);
            // destination path --https://<storage-account><container>/cases/materialid/blobnale
            final String destBlobPath = String.format("cases/%s/%s", uploadDate, blobName);

            final String contentType = "application/pdf";

            final Map<String, String> metadata = createBlobMetadata(documentId, materialID, caseIdStr, uploadDate);

            final String blobUrl = storageService.copyFromUrl(downloadUrl.get(), destBlobPath, contentType, metadata);

            final long sizeBytes = storageService.getBlobSize(destBlobPath);

            //save to  db
            final CaseDocument caseDocument =
                    buildDoc(documentId, UUID.fromString(caseIdStr),
                            blobName, blobUrl, contentType, sizeBytes);
            caseDocumentRepository.save(caseDocument);

        }
        return RepeatStatus.FINISHED;

    }

}
