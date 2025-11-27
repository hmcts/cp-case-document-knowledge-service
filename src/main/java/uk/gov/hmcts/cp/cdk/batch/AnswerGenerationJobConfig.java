package uk.gov.hmcts.cp.cdk.batch;

import uk.gov.hmcts.cp.cdk.batch.partition.QueryIdPartitioner;
import uk.gov.hmcts.cp.cdk.batch.partition.ReadyCasePartitioner;
import uk.gov.hmcts.cp.cdk.batch.verification.DocumentVerificationScheduler;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

/**
 * Answer-generation job (Answer Generation Job), runs only on cases whose documents are already INGESTED.
 *
 * <p>When triggered by {@link DocumentVerificationScheduler}, it is restricted
 * to the caseIds that were passed in the {@code caseIds} job parameter.</p>
 */
@Configuration
public class AnswerGenerationJobConfig {

    public static final String ANSWER_GENERATION_JOB_NAME = "answerGenerationJob";

    private static final String FLOW_PER_CASE_ANSWER = "perCaseAnswerFlow";
    private static final String STEP_PER_CASE_ANSWER_FLOW = "perCaseAnswerFlowStep";
    private static final String STEP_FILTER_READY_CASES_PARTITIONED =
            "step_filter_ready_cases_partitioned";
    private static final String STEP_6_GENERATE_ANSWERS_BY_QUERY_PARTITIONED =
            "step6_generate_answers_by_query_partitioned";
    private static final String STEP_6_GENERATE_ANSWER_SINGLE_QUERY =
            "step6_generate_answer_single_query";

    /**
     * Per-case flow for Answer Generation Job:
     * <ol>
     *   <li>Reserve answer version (step 5)</li>
     *   <li>Generate answers (step 6, partitioned by query)</li>
     * </ol>
     */
    @Bean
    public Flow perCaseAnswerFlow(final Step step5ReserveAnswerVersionPerCase,
                                  final Step step6GenerateAnswersByQueryPartitioned) {
        final FlowBuilder<SimpleFlow> flowBuilder = new FlowBuilder<>(FLOW_PER_CASE_ANSWER);
        return flowBuilder
                .start(step5ReserveAnswerVersionPerCase)
                .next(step6GenerateAnswersByQueryPartitioned)
                .end();
    }

    @Bean
    public Step perCaseAnswerFlowStep(final JobRepository jobRepository,
                                      final Flow perCaseAnswerFlow) {
        return new StepBuilder(STEP_PER_CASE_ANSWER_FLOW, jobRepository)
                .flow(perCaseAnswerFlow)
                .build();
    }

    /**
     * Case-level partitioning for Answer Generation Job on ready cases.
     *
     * <p>Uses {@link ReadyCasePartitioner}, which looks at job parameter {@code caseIds}
     * (if present) and selects INGESTED documents from {@code case_documents}.</p>
     */
    @Bean
    public Step stepFilterReadyCasesPartitioned(final JobRepository jobRepository,
                                                final ReadyCasePartitioner readyCasePartitioner,
                                                final Step perCaseAnswerFlowStep,
                                                @Qualifier("ingestionTaskExecutor") final TaskExecutor ingestionTaskExecutor,
                                                final PartitioningProperties partitioningProperties) {

        final int gridSize = partitioningProperties.caseGridSize();

        return new StepBuilder(STEP_FILTER_READY_CASES_PARTITIONED, jobRepository)
                .partitioner(STEP_PER_CASE_ANSWER_FLOW, readyCasePartitioner)
                .step(perCaseAnswerFlowStep)
                .gridSize(gridSize)
                .taskExecutor(ingestionTaskExecutor)
                .build();
    }

    /**
     * Query-level partitioning for step 6 in Answer Generation Job.
     * Reuses {@code step6GenerateAnswerSingleQuery} and {@link QueryIdPartitioner}.
     */
    @Bean
    public Step step6GenerateAnswersByQueryPartitioned(final JobRepository jobRepository,
                                                       final Step step6GenerateAnswerSingleQuery,
                                                       final QueryIdPartitioner queryIdPartitioner,
                                                       @Qualifier("queryPartitionTaskExecutor")
                                                       final TaskExecutor queryPartitionTaskExecutor,
                                                       final PartitioningProperties partitioningProperties) {

        final int gridSize = partitioningProperties.queryGridSize();

        return new StepBuilder(STEP_6_GENERATE_ANSWERS_BY_QUERY_PARTITIONED, jobRepository)
                .partitioner(STEP_6_GENERATE_ANSWER_SINGLE_QUERY, queryIdPartitioner)
                .step(step6GenerateAnswerSingleQuery)
                .gridSize(gridSize)
                .taskExecutor(queryPartitionTaskExecutor)
                .build();
    }

    @Bean
    public Job answerGenerationJob(final JobRepository jobRepository,
                                   final Step stepFilterReadyCasesPartitioned) {
        return new JobBuilder(ANSWER_GENERATION_JOB_NAME, jobRepository)
                .start(stepFilterReadyCasesPartitioned)
                .build();
    }
}
