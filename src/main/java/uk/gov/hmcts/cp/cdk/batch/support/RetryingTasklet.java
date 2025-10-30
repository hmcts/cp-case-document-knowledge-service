package uk.gov.hmcts.cp.cdk.batch.support;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;

/**
 * Decorator that enforces retry for any Tasklet by using a provided RetryTemplate.
 * This ensures PMD-friendly single responsibility and keeps tasklets unchanged.
 */
@RequiredArgsConstructor
public class RetryingTasklet implements Tasklet {

    private final Tasklet delegate;
    private final RetryTemplate retryTemplate;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        return retryTemplate.execute((RetryCallback<RepeatStatus, Exception>) context ->
                delegate.execute(contribution, chunkContext)
        );
    }
}