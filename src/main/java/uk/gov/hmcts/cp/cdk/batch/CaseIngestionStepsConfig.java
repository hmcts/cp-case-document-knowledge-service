package uk.gov.hmcts.cp.cdk.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.cdk.batch.tasklet.*;

@Configuration
@RequiredArgsConstructor
class CaseIngestionStepsConfig {

    private final PlatformTransactionManager txManager;

    private final FetchHearingsCasesTasklet step1;
    private final FilterEligibleCasesTasklet step2;
    private final UploadAndPersistTasklet step3;
    private final VerifyUploadTasklet step4;
    private final ReserveAnswerVersionTasklet step5;
    private final GenerateAnswersTasklet step6;

    @Bean
    Step step1FetchHearingsCasesWithSingleDefendant(final JobRepository repo) {
        return new StepBuilder("step1_fetch_hearings_cases", repo).tasklet(step1, txManager).build();
    }

    @Bean
    Step step2FilterCaseIdpcForSingleDefendant(final JobRepository repo) {
        return new StepBuilder("step2_check_single_defendant_idpc", repo).tasklet(step2, txManager).build();
    }

    @Bean
    Step step3_upload_and_persist(final JobRepository repo) {
        return new StepBuilder("step3_upload_and_persist", repo).tasklet(step3, txManager).build();
    }

    @Bean
    Step step4_verifyUpload_perCase(final JobRepository repo) {
        return new StepBuilder("step4_verify_upload_perCase", repo).tasklet(step4, txManager).build();
    }

    @Bean
    Step step5_reserveAnswerVersion_perCase(final JobRepository repo) {
        return new StepBuilder("step5_reserve_answer_version_perCase", repo).tasklet(step5, txManager).build();
    }

    @Bean
    Step step6_generateAnswers_perCase(final JobRepository repo) {
        return new StepBuilder("step6_generate_answers_perCase", repo).tasklet(step6, txManager).build();
    }
}

