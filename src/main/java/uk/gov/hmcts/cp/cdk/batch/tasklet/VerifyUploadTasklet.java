package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyUploadTasklet implements Tasklet {

    private static final String CTX_UPLOAD_VERIFIED = "uploadVerified";

    private final StorageService storageService;
    private final RetryTemplate storageCheckRetryTemplate;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final String caseIdStr = stepCtx.getString("caseId", null);

        if (caseIdStr == null) {
            log.debug("VerifyUpload skipped: no caseId in step context.");
            return RepeatStatus.FINISHED;
        }

        final UUID caseId = UUID.fromString(caseIdStr);
        final String path = blobPath(caseId);

        final boolean exists = storageCheckRetryTemplate.execute(ctx -> storageService.exists(path));
        stepCtx.put(CTX_UPLOAD_VERIFIED, exists);

        contribution.setExitStatus(exists ? ExitStatus.COMPLETED : ExitStatus.NOOP);

        log.info("VerifyUpload: path='{}' exists={}, exit={}", path, exists, contribution.getExitStatus());
        return RepeatStatus.FINISHED;
    }
}
