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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.time.OffsetDateTime.now;
import static java.time.format.DateTimeFormatter.ofPattern;


@Component
@RequiredArgsConstructor
public class UploadAndPersistTasklet implements Tasklet {
    private static ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final PlatformTransactionManager txManager;
    private CaseDocumentRepository caseDocumentRepository;

    private static Map<String, String> createBlobMetadata(final UUID documentId,
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
        //TODO  insert the docName which will be used to fetch the status
        final CaseDocument caseDocument = new CaseDocument();

        caseDocument.setDocId(docId);
        caseDocument.setCaseId(caseId);
        caseDocument.setDocName(docName);
        caseDocument.setBlobUri(blobUrl);
        caseDocument.setContentType(contentType);
        caseDocument.setSizeBytes(size);
        final OffsetDateTime now = now();
        caseDocument.setUploadedAt(now);
        caseDocument.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
        caseDocument.setIngestionPhaseAt(now);
        return caseDocument;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        @SuppressWarnings("unchecked") final List<String> materialIds =
                (List<String>) stepCtx.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS);

        @SuppressWarnings("unchecked") final Map<String, String> materialToCaseMap =
                (Map<String, String>) contribution.getStepExecution()
                        .getJobExecution()
                        .getExecutionContext()
                        .get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP);

        if (materialToCaseMap == null || materialToCaseMap.isEmpty()) {
            System.out.println("No material-to-case mapping found, skipping upload");//No PMD
            return RepeatStatus.FINISHED;
        }

        for (final String materialIdString : materialIds) {
            final UUID materialID = UUID.fromString(materialIdString);

            // Get the actual caseId for this materialId
            final String caseIdStr = materialToCaseMap.get(materialIdString);
            if (caseIdStr == null) {
                System.out.println("No caseId mapping found for materialId: " + materialIdString);//No PMD
                continue;
            }
            final Optional<String> downloadUrl = progressionClient.getMaterialDownloadUrl(materialID);


            if (downloadUrl.isEmpty()) {
                continue;
            }


            final UUID documentId = UUID.randomUUID();


            final String uploadDate = now().format(ofPattern("yyyyMMdd"));
            final String blobName = String.format("%s_%s.pdf", materialID, uploadDate);
            // destination path --https://<storage-account><container>/cases/materialid/blobnale
            final String destBlobPath = String.format("cases/%s/%s", uploadDate, blobName);

            final String contentType = "application/pdf";

            final Map<String, String> metadata = createBlobMetadata(documentId, materialID, caseIdStr, uploadDate);

            final String blobUrl = storageService.copyFromUrl(downloadUrl.get(), destBlobPath, contentType, metadata);

            final long sizeBytes = storageService.getBlobSize(destBlobPath);

            //save to db
            final CaseDocument caseDocument =
                    buildDoc(documentId, UUID.fromString(caseIdStr), blobName, blobUrl, contentType, sizeBytes);
            caseDocumentRepository.save(caseDocument);


        }
        return RepeatStatus.FINISHED;

    }

}
