package uk.gov.hmcts.cp.cdk.batch;

import uk.gov.hmcts.cp.cdk.batch.support.RetryingTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.FetchHearingsCasesTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.GenerateAnswersTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.ReserveAnswerVersionTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.ResolveMaterialForCaseTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.UploadAndPersistTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.VerifyUploadTasklet;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class CaseIngestionStepsConfig {

    private final PlatformTransactionManager txManager;
    private final RetryTemplate retryTemplate;

    private Step step(final String name, final JobRepository repo, final Tasklet tasklet) {
        return new StepBuilder(name, repo)
                .tasklet(new RetryingTasklet(tasklet, retryTemplate), txManager)
                .build();
    }

    @Bean
    public Step step1FetchHearingsCasesWithSingleDefendant(final JobRepository repo,
                                                           final FetchHearingsCasesTasklet fetchHearingsCasesTasklet) {
        return step("step1_fetch_hearings_cases", repo, fetchHearingsCasesTasklet);
    }

    @Bean
    public Step step2ResolveEligibleCaseWorker(final JobRepository repo,
                                               final ResolveMaterialForCaseTasklet tasklet) {
        return new StepBuilder("step2ResolveEligibleCaseWorker", repo)
                .tasklet(new RetryingTasklet(tasklet, retryTemplate), txManager)
                .build();
    }

    @Bean
    public Step step3UploadAndPersist(final JobRepository repo,
                                      final UploadAndPersistTasklet uploadAndPersistTasklet) {
        return new StepBuilder("step3_upload_and_persist_perCase", repo)
                .tasklet(uploadAndPersistTasklet)
                .transactionManager(new ResourcelessTransactionManager())
                .build();
    }

    @Bean
    public Step step4VerifyUploadPerCase(final JobRepository repo,
                                         final VerifyUploadTasklet verifyUploadTasklet) {
        return new StepBuilder("step4_verify_upload_perCase", repo)
                .tasklet(new RetryingTasklet(verifyUploadTasklet, retryTemplate))
                .build();
    }

    @Bean
    public Step step5ReserveAnswerVersionPerCase(final JobRepository repo,
                                                 final ReserveAnswerVersionTasklet tasklet) {
        return new StepBuilder("step5_reserve_answer_version_perCase", repo)
                .tasklet(tasklet)
                .transactionManager(new ResourcelessTransactionManager())
                .build();
    }

    @Bean
    public Step step6GenerateAnswerSingleQuery(final JobRepository repo,
                                               final GenerateAnswersTasklet generateAnswersTasklet) {
        return new StepBuilder("step6_generate_answer_single_query", repo)
                .tasklet(new RetryingTasklet(generateAnswersTasklet, retryTemplate))
                .transactionManager(new ResourcelessTransactionManager())
                .build();
    }
}
