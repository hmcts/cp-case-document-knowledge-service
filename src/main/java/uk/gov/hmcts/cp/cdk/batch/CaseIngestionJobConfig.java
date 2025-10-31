package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import uk.gov.hmcts.cp.cdk.batch.partition.MaterialToCasePartitioner;

import java.util.Collections;
import java.util.Map;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.JOB_NAME;

@Configuration
public class CaseIngestionJobConfig {

    @Bean
    public Flow perCaseFlow(final Step step3UploadAndPersist,
                            final Step step4VerifyUploadPerCase,
                            final Step step5ReserveAnswerVersionPerCase,
                            final Step step6GenerateAnswersPerCase) {

        final FlowBuilder<SimpleFlow> flowBuilder = new FlowBuilder<>("perCaseFlow");
        return flowBuilder.start(step3UploadAndPersist)
                .next(step4VerifyUploadPerCase)
                .next(step5ReserveAnswerVersionPerCase)
                .next(step6GenerateAnswersPerCase)
                .end();
    }

    @Bean
    public Step perCaseFlowStep(final JobRepository repo, final Flow perCaseFlow) {
        return new StepBuilder("perCaseFlowStep", repo)
                .flow(perCaseFlow)
                .build();
    }

    @Bean
    @StepScope
    @SuppressWarnings("unchecked")
    public Partitioner eligibleCasePartitioner(
            @Value("#{jobExecutionContext['" + BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY + "']}") final Map<String, String> materialToCaseMap) {

        final Map<String, String> safe = materialToCaseMap == null ? Collections.emptyMap() : materialToCaseMap;
        return new MaterialToCasePartitioner(safe);
    }

    @Bean
    public Step step3To6Partitioned(final JobRepository repo,
                                    final Step perCaseFlowStep,
                                    final Partitioner eligibleCasePartitioner,
                                    final TaskExecutor ingestionTaskExecutor) {
        return new StepBuilder("step3_to_6_partitioned", repo)
                .partitioner("perCaseFlowStep", eligibleCasePartitioner)
                .step(perCaseFlowStep)
                .gridSize(8)
                .taskExecutor(ingestionTaskExecutor)
                .build();
    }

    @Bean
    public Job caseIngestionJob(final JobRepository repo,
                                final Step step1FetchHearingsCasesWithSingleDefendant,
                                final Step step2FilterCaseIdpcForSingleDefendant,
                                final Step step3To6Partitioned) {
        return new JobBuilder(JOB_NAME, repo)
                .start(step1FetchHearingsCasesWithSingleDefendant)
                .next(step2FilterCaseIdpcForSingleDefendant)
                .next(step3To6Partitioned)
                .build();
    }
}