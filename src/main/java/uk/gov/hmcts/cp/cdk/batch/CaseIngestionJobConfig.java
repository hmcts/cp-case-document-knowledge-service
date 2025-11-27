package uk.gov.hmcts.cp.cdk.batch;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.JOB_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY;

import uk.gov.hmcts.cp.cdk.batch.partition.MaterialMappingAggregator;

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

/**
 * Defines the ingestion job structure and partitioning flows.
 *
 * <p>Job A:
 * <ul>
 *   <li>Step 1: fetch hearings / cases</li>
 *   <li>Step 2: filter eligible cases (partitioned)</li>
 *   <li>Step 3: upload &amp; persist documents (partitioned per case/material)</li>
 * </ul>
 *
 * <p>Answer generation is handled by Job B once documents are fully INGESTED.</p>
 */
@Configuration
public class CaseIngestionJobConfig {

    private static final String FLOW_PER_CASE = "perCaseFlow";
    private static final String STEP_PER_CASE_FLOW = "perCaseFlowStep";
    private static final String STEP_3_PARTITIONED = "step3_partitioned";
    private static final String STEP_2_FILTER_PARTITIONED = "step2_filter_case_partitioned";

    /**
     * Per-case flow: only upload + persist.
     */
    @Bean
    public Flow perCaseFlow(final Step step3UploadAndPersist) {
        final FlowBuilder<SimpleFlow> flowBuilder = new FlowBuilder<>(FLOW_PER_CASE);
        return flowBuilder
                .start(step3UploadAndPersist)
                .end();
    }

    @Bean
    public Step perCaseFlowStep(final JobRepository jobRepository,
                                final Flow perCaseFlow) {
        return new StepBuilder(STEP_PER_CASE_FLOW, jobRepository)
                .flow(perCaseFlow)
                .build();
    }

    /**
     * Case-level partitioning of per-case flow (step 3).
     */
    @Bean
    public Step step3To6Partitioned(final JobRepository jobRepository,
                                    final Step perCaseFlowStep,
                                    final Partitioner eligibleMaterialCasePartitioner,
                                    @Qualifier("ingestionTaskExecutor") final TaskExecutor ingestionTaskExecutor,
                                    final PartitioningProperties partitioningProperties) {

        final int gridSize = partitioningProperties.caseGridSize();

        return new StepBuilder(STEP_3_PARTITIONED, jobRepository)
                .partitioner(STEP_PER_CASE_FLOW, eligibleMaterialCasePartitioner)
                .step(perCaseFlowStep)
                .gridSize(gridSize)
                .taskExecutor(ingestionTaskExecutor)
                .build();
    }

    /**
     * Listener to promote material-to-case map from step2 worker executions.
     */
    @Bean
    public ExecutionContextPromotionListener eligibleMaterialListener() {
        final ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[]{CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY});
        return listener;
    }

    @Bean
    public Step step2FilterEligibleCasesPartitioned(final JobRepository jobRepository,
                                                    final Partitioner caseIdPartitioner,
                                                    final Step step2ResolveEligibleCaseWorker,
                                                    @Qualifier("ingestionTaskExecutor") final TaskExecutor ingestionTaskExecutor,
                                                    final MaterialMappingAggregator materialMappingAggregator,
                                                    final PartitioningProperties partitioningProperties) {

        final int gridSize = partitioningProperties.filterGridSize();

        return new StepBuilder(STEP_2_FILTER_PARTITIONED, jobRepository)
                .partitioner(step2ResolveEligibleCaseWorker.getName(), caseIdPartitioner)
                .step(step2ResolveEligibleCaseWorker)
                .aggregator(materialMappingAggregator)
                .listener(eligibleMaterialListener())
                .taskExecutor(ingestionTaskExecutor)
                .gridSize(gridSize)
                .build();
    }

    /**
     * Job A – uploads documents and enqueues verification, but does not wait for it.
     */
    @Bean
    public Job caseIngestionJob(final JobRepository jobRepository,
                                final Step step1FetchHearingsCasesWithSingleDefendant,
                                final Step step2FilterEligibleCasesPartitioned,
                                final Step step3To6Partitioned) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(step1FetchHearingsCasesWithSingleDefendant)
                .next(step2FilterEligibleCasesPartitioned)
                .next(step3To6Partitioned)
                .build();
    }
}
