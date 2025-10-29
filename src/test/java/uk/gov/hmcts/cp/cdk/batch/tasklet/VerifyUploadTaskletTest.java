package uk.gov.hmcts.cp.cdk.batch.tasklet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.retry.support.RetryTemplate;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.blobPath;

@DisplayName("VerifyUploadTasklet tests")
@ExtendWith(MockitoExtension.class)
class VerifyUploadTaskletTest {

    @Mock private StorageService storageService;
    @Mock private StepContribution contribution;
    @Mock private ChunkContext chunkContext;
    @Mock private StepExecution stepExecution;

    private RetryTemplate retryTemplate() {
        return RetryTemplate
                .builder()
                .maxAttempts(1)
                .fixedBackoff(1)   // ← must be >= 1
                .build();
    }

    private VerifyUploadTasklet newTasklet() {
        return new VerifyUploadTasklet(storageService, retryTemplate());
    }

    @Test
    @DisplayName("Sets COMPLETED when blob exists, stores uploadVerified=true")
    void setsCompletedWhenExists() throws Exception {

        UUID caseId = UUID.randomUUID();
        String path = blobPath(caseId);

        ExecutionContext stepCtx = new ExecutionContext();
        stepCtx.putString("caseId", caseId.toString());

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        when(storageService.exists(path)).thenReturn(true);

        RepeatStatus status = newTasklet().execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepCtx.get("uploadVerified")).isEqualTo(true);
        verify(storageService, times(1)).exists(path);
        verify(contribution, times(1)).setExitStatus(ExitStatus.COMPLETED);
    }

    @Test
    @DisplayName("Sets NOOP when blob missing, stores uploadVerified=false")
    void setsNoopWhenMissing() throws Exception {

        UUID caseId = UUID.randomUUID();
        String path = blobPath(caseId);

        ExecutionContext stepCtx = new ExecutionContext();
        stepCtx.putString("caseId", caseId.toString());

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        when(storageService.exists(path)).thenReturn(false);

        RepeatStatus status = newTasklet().execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepCtx.get("uploadVerified")).isEqualTo(false);
        verify(storageService, times(1)).exists(path);
        verify(contribution, times(1)).setExitStatus(ExitStatus.NOOP);
    }

    @Test
    @DisplayName("Finishes immediately when caseId absent; no storage access")
    void finishesWhenNoCaseId() throws Exception {
        ExecutionContext stepCtx = new ExecutionContext(); // no caseId
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);

        RepeatStatus status = newTasklet().execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(storageService);
        verify(contribution, never()).setExitStatus(any());
    }
}
