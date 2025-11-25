package uk.gov.hmcts.cp.cdk.batch;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.JOB_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY;

import uk.gov.hmcts.cp.cdk.batch.partition.MaterialMappingAggregator;
import uk.gov.hmcts.cp.cdk.batch.partition.QueryIdPartitioner;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

@Configuration
public class CaseIngestionJobConfig {

    /**
     * Per-case flow:
     *  3. Upload + persist documents
     *  4. Verify upload
     *  5. Reserve answer version
     *  6. Generate answers (partitioned by query)
     */
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

    /**
     * Case-level partitioning of steps 3–6.
     * Uses the shared ingestionTaskExecutor.
     */
    @Bean
    public Step step3To6Partitioned(final JobRepository repo,
                                    final Step perCaseFlowStep,
                                    final Partitioner eligibleMaterialCasePartitioner,
                                    @Qualifier("ingestionTaskExecutor")
                                    final TaskExecutor ingestionTaskExecutor,
                                    final PartitioningProperties partitionProps) {
        return new StepBuilder("step3_to_6_partitioned", repo)
                .partitioner("perCaseFlowStep", eligibleMaterialCasePartitioner)
                .step(perCaseFlowStep)
                .gridSize(partitionProps.caseGridSize())
                .taskExecutor(ingestionTaskExecutor)
                .build();
    }

    /**
     * Query-level partitioning for step 6.
     * Uses dedicated queryPartitionTaskExecutor.
     */
    @Bean
    public Step step6GenerateAnswersByQueryPartitioned(final JobRepository repo,
                                                       final Step step6GenerateAnswerSingleQuery,
                                                       final QueryIdPartitioner queryIdPartitioner,
                                                       @Qualifier("queryPartitionTaskExecutor")
                                                       final TaskExecutor queryPartitionTaskExecutor,
                                                       final PartitioningProperties partitionProps) {
        return new StepBuilder("step6_generate_answers_by_query_partitioned", repo)
                .partitioner("step6_generate_answer_single_query", queryIdPartitioner)
                .step(step6GenerateAnswerSingleQuery)
                .gridSize(partitionProps.queryGridSize())
                .taskExecutor(queryPartitionTaskExecutor)
                .build();
    }

    /**
     * Promotes material-to-case map from step2 worker executions to the master
     * execution context for later partitioning.
     */
    @Bean
    public ExecutionContextPromotionListener eligibleMaterialListener() {
        final ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[]{CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY});
        return listener;
    }

    /**
     * Step 2 – partition by case ID, resolve eligible materials per case.
     */
    @Bean
    public Step step2FilterEligibleCasesPartitioned(final JobRepository repo,
                                                    final Partitioner caseIdPartitioner,
                                                    final Step step2ResolveEligibleCaseWorker,
                                                    @Qualifier("ingestionTaskExecutor")
                                                    final TaskExecutor ingestionTaskExecutor,
                                                    final MaterialMappingAggregator materialMappingAggregator,
                                                    final PartitioningProperties partitionProps) {
        return new StepBuilder("step2_filter_case_partitioned", repo)
                .partitioner(step2ResolveEligibleCaseWorker.getName(), caseIdPartitioner)
                .step(step2ResolveEligibleCaseWorker)
                .aggregator(materialMappingAggregator)
                .listener(eligibleMaterialListener())
                .taskExecutor(ingestionTaskExecutor)
                .gridSize(partitionProps.filterGridSize())
                .build();
    }

    /**
     * Full case ingestion job:
     *  1. Fetch hearings / cases
     *  2. Partitioned filter of eligible cases/materials
     *  3. Partitioned per-case flow (upload, verify, reserve, generate answers)
     */
    @Bean
    public Job caseIngestionJob(final JobRepository repo,
                                final Step step1FetchHearingsCasesWithSingleDefendant,
                                final Step step2FilterEligibleCasesPartitioned,
                                final Step step3To6Partitioned) {
        return new JobBuilder(JOB_NAME, repo)
                .start(step1FetchHearingsCasesWithSingleDefendant)
                .next(step2FilterEligibleCasesPartitioned)
                .next(step3To6Partitioned)
                .build();
    }
}
