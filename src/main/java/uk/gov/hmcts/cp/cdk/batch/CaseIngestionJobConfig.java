package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import uk.gov.hmcts.cp.cdk.batch.partition.QueryIdPartitioner;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.JOB_NAME;

@Configuration
public class CaseIngestionJobConfig {

    @Bean
    public Flow perCaseFlow(final Step step3UploadAndPersist,
                            final Step step4VerifyUploadPerCase,
                            final Step step5ReserveAnswerVersionPerCase,
                            final Step step6GenerateAnswersByQueryPartitioned) {

        final FlowBuilder<SimpleFlow> flowBuilder = new FlowBuilder<>("perCaseFlow");
        return flowBuilder.start(step3UploadAndPersist)
                .next(step4VerifyUploadPerCase)
                .next(step5ReserveAnswerVersionPerCase)
                .next(step6GenerateAnswersByQueryPartitioned)
                .end();
    }

    @Bean
    public Step perCaseFlowStep(final JobRepository repo, final Flow perCaseFlow) {
        return new StepBuilder("perCaseFlowStep", repo)
                .flow(perCaseFlow)
                .build();
    }

    @Bean
    public Step step3To6Partitioned(final JobRepository repo,
                                    final Step perCaseFlowStep,
                                    @Qualifier("eligibleCasePartitioner") final Partitioner eligibleCasePartitioner,
                                    final TaskExecutor ingestionTaskExecutor) {
        return new StepBuilder("step3_to_6_partitioned", repo)
                .partitioner("perCaseFlowStep", eligibleCasePartitioner)
                .step(perCaseFlowStep)
                .gridSize(8)
                .taskExecutor(ingestionTaskExecutor)
                .build();
    }

    @Bean
    public Step step6GenerateAnswersByQueryPartitioned(final JobRepository repo,
                                                       final Step step6GenerateAnswerSingleQuery,
                                                       final QueryIdPartitioner queryIdPartitioner,
                                                       final TaskExecutor ingestionTaskExecutor) {
        return new StepBuilder("step6_generate_answers_by_query_partitioned", repo)
                .partitioner("step6_generate_answer_single_query", queryIdPartitioner)
                .step(step6GenerateAnswerSingleQuery)
                .gridSize(8) // up to 8 queries in parallel per case; tune as needed
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
