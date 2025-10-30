package uk.gov.hmcts.cp.cdk.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.cdk.batch.support.RetryingTasklet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;

@SpringBatchTest
@SpringBootTest(classes = {
        BatchConfig.class,
        BatchInfraConfig.class,
        CaseIngestionJobConfig.class,
        CaseIngestionJobE2ETest.TestStepsConfig.class
}, properties = "spring.main.allow-bean-definition-overriding=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CaseIngestionJobE2ETest {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private Probe probe;

    //@Test
    //@DisplayName("E2E: two eligible cases partitioned across steps 3–6; step3 retries once per case; job completes")
    void endToEnd_withTwoCases_retriesAndPartitions_workflowCompletes() throws Exception {
        JobExecution jobExecution = jobOperatorTestUtils.startJob(uniqueParams());

        assertThat(jobExecution.getStatus()).as("Job status").isEqualTo(BatchStatus.COMPLETED);

        List<String> stepNames = jobExecution.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .sorted()
                .collect(toList());

        // step1 and step2 are non-partitioned: run once
        assertThat(probe.step1Count.get()).as("step1 single execution").isEqualTo(1);
        assertThat(probe.step2Count.get()).as("step2 single execution").isEqualTo(1);

        // Partitioned flow created two worker executions of perCaseFlowStep
        long workerFlowExecutions = stepNames.stream().filter(n -> n.startsWith("perCaseFlowStep:")).count();
        assertThat(workerFlowExecutions).as("worker perCaseFlowStep executions").isEqualTo(2);

        // Retry happened in step3: first attempt fails per docId, second succeeds
        assertThat(probe.step3AttemptsByDocId).as("attempts recorded").hasSize(2);
        assertThat(probe.step3AttemptsByDocId.get("m1").get()).as("m1 attempts").isEqualTo(2);
        assertThat(probe.step3AttemptsByDocId.get("m2").get()).as("m2 attempts").isEqualTo(2);

        // Steps 3..6 saw both docIds with correct caseIds propagated by the partitioner
        assertThat(probe.step3SuccessDocIds).containsExactlyInAnyOrder("m1", "m2");
        assertThat(probe.step4Seen).containsKeys("m1", "m2");
        assertThat(probe.step5Seen).containsKeys("m1", "m2");
        assertThat(probe.step6Seen).containsKeys("m1", "m2");
        assertThat(probe.step4Seen.get("m1")).isEqualTo("c1");
        assertThat(probe.step4Seen.get("m2")).isEqualTo("c2");
        assertThat(probe.step5Seen.get("m1")).isEqualTo("c1");
        assertThat(probe.step5Seen.get("m2")).isEqualTo("c2");
        assertThat(probe.step6Seen.get("m1")).isEqualTo("c1");
        assertThat(probe.step6Seen.get("m2")).isEqualTo("c2");
    }

    @Test
    @DisplayName("E2E: no eligible cases -> partitioned flow skipped; job still completes")
    void endToEnd_withNoEligibleCases_partitionsSkip_workflowCompletes() throws Exception {
        // Reset and configure Filter tasklet to emit empty map
        probe.reset();
        probe.emitEmptyMap = true;

        JobExecution jobExecution = jobOperatorTestUtils.startJob(uniqueParams());

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Step1 & Step2 still run once
        assertThat(probe.step1Count.get()).isEqualTo(1);
        assertThat(probe.step2Count.get()).isEqualTo(1);

        // No worker flow steps when there are no partitions
        List<String> stepNames = jobExecution.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .collect(toList());
        long workerFlowExecutions = stepNames.stream().filter(n -> n.startsWith("perCaseFlowStep:")).count();
        assertThat(workerFlowExecutions).isEqualTo(0);

        // No per-doc operations happened
        assertThat(probe.step3SuccessDocIds).isEmpty();
        assertThat(probe.step4Seen).isEmpty();
        assertThat(probe.step5Seen).isEmpty();
        assertThat(probe.step6Seen).isEmpty();
    }

    private JobParameters uniqueParams() {
        return new JobParametersBuilder()
                .addLong("ts", System.currentTimeMillis())
                .addString("runId", java.util.UUID.randomUUID().toString()) // <— ensure new JobInstance
                .toJobParameters();
    }

    /**
     * Shared state object to capture what the test tasklets observed/did.
     */
    static class Probe {
        final AtomicInteger step1Count = new AtomicInteger();
        final AtomicInteger step2Count = new AtomicInteger();
        final Map<String, AtomicInteger> step3AttemptsByDocId = new ConcurrentHashMap<>();
        final Set<String> step3SuccessDocIds = new ConcurrentSkipListSet<>();
        final Map<String, String> step4Seen = new ConcurrentHashMap<>();
        final Map<String, String> step5Seen = new ConcurrentHashMap<>();
        final Map<String, String> step6Seen = new ConcurrentHashMap<>();
        volatile boolean emitEmptyMap = false;

        void reset() {
            step1Count.set(0);
            step2Count.set(0);
            step3AttemptsByDocId.clear();
            step3SuccessDocIds.clear();
            step4Seen.clear();
            step5Seen.clear();
            step6Seen.clear();
            emitEmptyMap = false;
        }
    }

    @TestConfiguration
    static class TestStepsConfig {

        @Bean(name = "ingestionTaskExecutor")
        @Primary
        public TaskExecutor ingestionTaskExecutor() {
            return new SyncTaskExecutor(); // run partitions inline; no STARTING leftovers
        }

        @Bean
        @Primary
        public PlatformTransactionManager transactionManager() {
            return new ResourcelessTransactionManager();
        }


        @Bean
        @Primary
        public Probe probe() {
            return new Probe();
        }

        // ----- Step beans that mirror production names -----

        @Bean
        public Step step1FetchHearingsCasesWithSingleDefendant(final JobRepository repo,
                                                               final PlatformTransactionManager txManager,
                                                               final RetryTemplate retryTemplate,
                                                               final Probe probe) {
            Tasklet t = (contribution, chunkContext) -> {
                probe.step1Count.incrementAndGet();
                return RepeatStatus.FINISHED;
            };
            return new StepBuilder("step1_fetch_hearings_cases", repo)
                    .tasklet(new RetryingTasklet(t, retryTemplate), txManager)
                    .build();
        }

        @Bean
        public Step step2FilterCaseIdpcForSingleDefendant(final JobRepository repo,
                                                          final PlatformTransactionManager txManager,
                                                          final RetryTemplate retryTemplate,
                                                          final Probe probe) {

            Tasklet t = (contribution, chunkContext) -> {
                probe.step2Count.incrementAndGet();
                ExecutionContext jobCtx = chunkContext.getStepContext()
                        .getStepExecution()
                        .getJobExecution()
                        .getExecutionContext();
                Map<String, String> map = probe.emitEmptyMap
                        ? Collections.emptyMap()
                        : new LinkedHashMap<>(Map.of("m1", "c1", "m2", "c2"));
                jobCtx.put(CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY, map);
                return RepeatStatus.FINISHED;
            };
            return new StepBuilder("step2_filter_case_idpc", repo)
                    .tasklet(new RetryingTasklet(t, retryTemplate), txManager)
                    .build();
        }

        @Bean
        public Step step3UploadAndPersist(final JobRepository repo,
                                          final PlatformTransactionManager txManager,
                                          final RetryTemplate retryTemplate,
                                          final Probe probe) {
            Tasklet t = (contribution, chunkContext) -> {
                ExecutionContext ctx = chunkContext.getStepContext().getStepExecution().getExecutionContext();
                String docId = ctx.getString(CTX_DOC_ID_KEY, null);
                String caseId = ctx.getString("caseId", null);
                // Simulate transient failure first time per docId to prove retry kicks in
                AtomicInteger attempts = probe.step3AttemptsByDocId.computeIfAbsent(docId, k -> new AtomicInteger());
                int n = attempts.incrementAndGet();
                if (n == 1) {
                    throw new RuntimeException("transient upload failure for " + docId);
                }
                // Success path
                probe.step3SuccessDocIds.add(docId);
                return RepeatStatus.FINISHED;
            };
            return new StepBuilder("step3_upload_and_persist_perCase", repo)
                    .tasklet(new RetryingTasklet(t, retryTemplate), txManager)
                    .build();
        }

        @Bean
        public Step step4VerifyUploadPerCase(final JobRepository repo,
                                             final PlatformTransactionManager txManager,
                                             final RetryTemplate retryTemplate,
                                             final Probe probe) {
            Tasklet t = (contribution, chunkContext) -> {
                ExecutionContext ctx = chunkContext.getStepContext().getStepExecution().getExecutionContext();
                String docId = ctx.getString(CTX_DOC_ID_KEY, null);
                String caseId = ctx.getString("caseId", null);
                probe.step4Seen.put(docId, caseId);
                return RepeatStatus.FINISHED;
            };
            return new StepBuilder("step4_verify_upload_perCase", repo)
                    .tasklet(new RetryingTasklet(t, retryTemplate), txManager)
                    .build();
        }

        @Bean
        public Step step5ReserveAnswerVersionPerCase(final JobRepository repo,
                                                     final PlatformTransactionManager txManager,
                                                     final RetryTemplate retryTemplate,
                                                     final Probe probe) {
            Tasklet t = (contribution, chunkContext) -> {
                ExecutionContext ctx = chunkContext.getStepContext().getStepExecution().getExecutionContext();
                String docId = ctx.getString(CTX_DOC_ID_KEY, null);
                String caseId = ctx.getString("caseId", null);
                probe.step5Seen.put(docId, caseId);
                return RepeatStatus.FINISHED;
            };
            return new StepBuilder("step5_reserve_answer_version_perCase", repo)
                    .tasklet(new RetryingTasklet(t, retryTemplate), txManager)
                    .build();
        }

        @Bean
        public Step step6GenerateAnswersPerCase(final JobRepository repo,
                                                final PlatformTransactionManager txManager,
                                                final RetryTemplate retryTemplate,
                                                final Probe probe) {
            Tasklet t = (contribution, chunkContext) -> {
                ExecutionContext ctx = chunkContext.getStepContext().getStepExecution().getExecutionContext();
                String docId = ctx.getString(CTX_DOC_ID_KEY, null);
                String caseId = ctx.getString("caseId", null);
                probe.step6Seen.put(docId, caseId);
                return RepeatStatus.FINISHED;
            };
            return new StepBuilder("step6_generate_answers_perCase", repo)
                    .tasklet(new RetryingTasklet(t, retryTemplate), txManager)
                    .build();
        }
    }
}
