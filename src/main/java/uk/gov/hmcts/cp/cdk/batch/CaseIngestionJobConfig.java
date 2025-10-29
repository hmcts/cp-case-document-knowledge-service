package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_ELIGIBLE_CASE_IDS;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.JOB_NAME;

@Configuration
public class CaseIngestionJobConfig {

    @Bean
    public TaskExecutor ingestionTaskExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ingestion-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }

    @Bean
    public RetryTemplate storageCheckRetryTemplate() {
        final RetryTemplate template = new RetryTemplate();
        final SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(5);
        final FixedBackOffPolicy backoff = new FixedBackOffPolicy();
        backoff.setBackOffPeriod(2000L);
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backoff);
        return template;
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    @Bean
    public Partitioner eligibleCasePartitioner() {
        return gridSize -> {
            final Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
            final StepContext sync = StepSynchronizationManager.getContext();

            List<String> ids = List.of();
            if (sync != null) {
                final Map<String, Object> jobCtx = sync.getJobExecutionContext();
                final Object object = (jobCtx != null) ? jobCtx.get(CTX_ELIGIBLE_CASE_IDS) : null;
                if (object instanceof List<?>) {
                    @SuppressWarnings("unchecked") final List<String> tmp = (List<String>) object;
                    ids = tmp;
                }
            }
            int partition = 0;
            for (final String id : ids) {
                final ExecutionContext executionContext = new ExecutionContext();
                executionContext.putString("caseId", id);
                partitions.put("case-" + (partition++), executionContext);
            }
            return partitions;
        };
    }

    @SuppressWarnings("PMD.FormalParameterNamingConventions")
    @Bean
    public Step caseWorkerFlowStep(final JobRepository repo,
                                   final Step step3_upload_and_persist,
                                   final Step step4_verifyUpload_perCase,
                                   final Step step5_reserveAnswerVersion_perCase,
                                   final Step step6_generateAnswers_perCase) {
        final Flow flow = new FlowBuilder<SimpleFlow>("caseWorkerFlow")
                .start(step3_upload_and_persist)
                .next(step4_verifyUpload_perCase)
                .on(ExitStatus.COMPLETED.getExitCode()).to(step5_reserveAnswerVersion_perCase)
                .from(step5_reserveAnswerVersion_perCase).on("*").to(step6_generateAnswers_perCase)
                .from(step4_verifyUpload_perCase).on("*").end()
                .build();
        return new StepBuilder("caseWorkerFlowStep", repo).flow(flow).build();
    }

    @SuppressWarnings("PMD.MethodNamingConventions")
    @Bean
    public Step step3to6_partitioned(final JobRepository repo,
                                     final Partitioner eligibleCasePartitioner,
                                     final Step caseWorkerFlowStep,
                                     final TaskExecutor ingestionTaskExecutor) {
        return new StepBuilder("step3to6_partitioned", repo)
                .partitioner("caseWorkerFlowStep", eligibleCasePartitioner)
                .step(caseWorkerFlowStep)
                .gridSize(8)
                .taskExecutor(ingestionTaskExecutor)
                .build();
    }

    @SuppressWarnings("PMD.FormalParameterNamingConventions")
    @Bean
    public Job caseIngestionJob(final JobRepository repo,
                                final Step step1FetchHearingsCasesWithSingleDefendant,
                                final Step step2FilterCaseIdpcForSingleDefendant,
                                final Step step3to6_partitioned) {
        return new JobBuilder(JOB_NAME, repo)
                .start(step1FetchHearingsCasesWithSingleDefendant)
                .next(step2FilterCaseIdpcForSingleDefendant)
                .next(step3to6_partitioned)
                .build();
    }
}
