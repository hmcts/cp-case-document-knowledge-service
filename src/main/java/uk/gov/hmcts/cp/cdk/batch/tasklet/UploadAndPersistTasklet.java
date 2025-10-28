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

import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.query.QueryClient;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.blobPath;

@Component
@RequiredArgsConstructor
public class UploadAndPersistTasklet implements Tasklet {
    private final QueryClient queryClient;
    private final StorageService storage;
    private final CaseDocumentRepository caseDocumentRepository;
    private final PlatformTransactionManager txManager;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final String caseIdStr = stepCtx.getString("caseId", null);
        if (caseIdStr == null) return RepeatStatus.FINISHED;

        final UUID caseId = UUID.fromString(caseIdStr);
        final Optional<LatestMaterialInfo> meta = queryClient.getCourtDocuments(caseId);

        return RepeatStatus.FINISHED;
    }
}
