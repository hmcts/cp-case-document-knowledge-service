package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.blobPath;

@Component
@RequiredArgsConstructor
public class VerifyUploadTasklet implements Tasklet {
    private final StorageService storageService;
    private final RetryTemplate storageCheckRetryTemplate;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final String caseIdStr = stepCtx.getString("caseId", null);
        if (caseIdStr == null) return RepeatStatus.FINISHED;

        final UUID caseId = UUID.fromString(caseIdStr);
        final boolean ok = storageCheckRetryTemplate.execute(r -> storageService.exists(blobPath(caseId)));
        contribution.setExitStatus(ok ? ExitStatus.COMPLETED : ExitStatus.NOOP);
        return RepeatStatus.FINISHED;
    }
}

