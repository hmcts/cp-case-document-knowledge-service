package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.query.QueryClient;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.blobPath;

@Component
@RequiredArgsConstructor
public class UploadAndPersistTasklet implements Tasklet {
    private final ProgressionClient progressionClient;
    private final StorageService storage;
    private final PlatformTransactionManager txManager;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        @SuppressWarnings("unchecked")
        final List<String> rawEligibleIds =
                (List<String> )stepCtx.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS);


        for (final String idStr : rawEligibleIds) {
            final UUID materialID = UUID.fromString(idStr);
            final Optional<String> downloadUrl = progressionClient.getMaterialDownloadUrl(materialID);


            if (downloadUrl.isEmpty()) {
                continue;
            }
            //
            // this needs to be disucssed and change
            final QueryClient.CourtDocMeta meta = new QueryClient.CourtDocMeta(true,true,downloadUrl
                    .get(),"application/pdf",0L);

            /** need to update this code with copyurl once we have destination path
             try (InputStream inputStream = queryClient.downloadIdpc(downloadUrl.get())) {
             final long size = meta.sizeBytes() == null ? 0L : meta.sizeBytes();
             final String contentType = meta.contentType();
             final String blobPath = buildIdpcBlobPath(materialID);
             final String blobUrl = storageService.upload(blobPath, inputStream, size, contentType);
             final CaseDocument caseDocument =
             buildCaseDocument(materialID, blobUrl, contentType, size);
             caseDocumentRepository.save(caseDocument);
             }
             **/
        }
        return RepeatStatus.FINISHED;


    }
}
