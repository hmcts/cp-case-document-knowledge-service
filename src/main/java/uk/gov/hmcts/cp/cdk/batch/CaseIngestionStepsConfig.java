package uk.gov.hmcts.cp.cdk.batch;

import uk.gov.hmcts.cp.cdk.batch.support.RetryingTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.FetchHearingsCasesTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.GenerateAnswersTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.ReserveAnswerVersionTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.ResolveMaterialForCaseTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.UploadAndPersistTasklet;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Declares steps used by ingestion job and answer-generation job.
 */
@Configuration
@RequiredArgsConstructor
public class CaseIngestionStepsConfig {

    private final PlatformTransactionManager platformTransactionManager;
    private final RetryTemplate retryTemplate;

    private Step createRetryingStep(final String stepName,
                                    final JobRepository jobRepository,
                                    final Tasklet delegateTasklet) {
        final RetryingTasklet retryingTasklet = new RetryingTasklet(delegateTasklet, retryTemplate);
        return new StepBuilder(stepName, jobRepository)
                .tasklet(retryingTasklet, platformTransactionManager)
                .build();
    }

    @Bean
    public Step step1FetchHearingsCasesWithSingleDefendant(final JobRepository jobRepository,
                                                           final FetchHearingsCasesTasklet fetchHearingsCasesTasklet) {
        return createRetryingStep("step1_fetch_hearings_cases", jobRepository, fetchHearingsCasesTasklet);
    }

    @Bean
    public Step step2ResolveEligibleCaseWorker(final JobRepository jobRepository,
                                               final ResolveMaterialForCaseTasklet resolveMaterialForCaseTasklet) {
        return createRetryingStep("step2ResolveEligibleCaseWorker", jobRepository, resolveMaterialForCaseTasklet);
    }

    @Bean
    public Step step3UploadAndPersist(final JobRepository jobRepository,
                                      final UploadAndPersistTasklet uploadAndPersistTasklet) {
        final ResourcelessTransactionManager resourcelessTransactionManager =
                new ResourcelessTransactionManager();

        return new StepBuilder("step3_upload_and_persist_perCase", jobRepository)
                .tasklet(uploadAndPersistTasklet, resourcelessTransactionManager)
                .build();
    }

    // Step 5 and 6 are used by Answer Generation Job (answer-generation job).

    @Bean
    public Step step5ReserveAnswerVersionPerCase(final JobRepository jobRepository,
                                                 final ReserveAnswerVersionTasklet reserveAnswerVersionTasklet) {
        final ResourcelessTransactionManager resourcelessTransactionManager =
                new ResourcelessTransactionManager();

        return new StepBuilder("step5_reserve_answer_version_perCase", jobRepository)
                .tasklet(reserveAnswerVersionTasklet, resourcelessTransactionManager)
                .build();
    }

    @Bean
    public Step step6GenerateAnswerSingleQuery(final JobRepository jobRepository,
                                               final GenerateAnswersTasklet generateAnswersTasklet) {
        final ResourcelessTransactionManager resourcelessTransactionManager =
                new ResourcelessTransactionManager();
        final RetryingTasklet retryingTasklet =
                new RetryingTasklet(generateAnswersTasklet, retryTemplate);

        return new StepBuilder("step6_generate_answer_single_query", jobRepository)
                .tasklet(retryingTasklet, resourcelessTransactionManager)
                .build();
    }
}
