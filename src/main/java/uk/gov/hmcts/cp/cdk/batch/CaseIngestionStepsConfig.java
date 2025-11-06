package uk.gov.hmcts.cp.cdk.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.cdk.batch.support.RetryingTasklet;
import uk.gov.hmcts.cp.cdk.batch.tasklet.*;

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
    public Step step2FilterCaseIdpcForSingleDefendant(final JobRepository repo,
                                                      final FilterEligibleCasesTasklet filterEligibleCasesTasklet) {
        return step("step2_filter_case_idpc", repo, filterEligibleCasesTasklet);
    }

    @Bean
    public Step step3UploadAndPersist(final JobRepository repo,
                                      final UploadAndPersistTasklet uploadAndPersistTasklet) {
        return step("step3_upload_and_persist_perCase", repo, uploadAndPersistTasklet);
    }

    @Bean
    public Step step4VerifyUploadPerCase(final JobRepository repo,
                                         final VerifyUploadTasklet verifyUploadTasklet) {
        return step("step4_verify_upload_perCase", repo, verifyUploadTasklet);
    }

    @Bean
    public Step step5ReserveAnswerVersionPerCase(final JobRepository repo,
                                                 final ReserveAnswerVersionTasklet reserveAnswerVersionTasklet) {
        return step("step5_reserve_answer_version_perCase", repo, reserveAnswerVersionTasklet);
    }

    // Old (kept but unused in flow): step6_generate_answers_perCase
    @Bean
    public Step step6GenerateAnswersPerCase(final JobRepository repo,
                                            final GenerateAnswersTasklet generateAnswersTasklet) {
        return step("step6_generate_answers_perCase", repo, generateAnswersTasklet);
    }

    @Bean
    public Step step6GenerateAnswerSingleQuery(final JobRepository repo,
                                               final GenerateAnswersTasklet generateAnswersTasklet) {
        return new StepBuilder("step6_generate_answer_single_query", repo)
                .tasklet(new RetryingTasklet(generateAnswersTasklet, retryTemplate))
                .build();
    }
}
