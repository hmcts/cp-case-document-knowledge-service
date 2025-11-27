package uk.gov.hmcts.cp.cdk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "cdk.ingestion.verify.scheduler")
public class VerifySchedulerProperties {

    /**
     * Master toggle for scheduler.
     */
    private boolean enabled = true;

    /**
     * Fixed delay between polls in milliseconds.
     */
    private long delayMs = 1_000L;

    /**
     * Maximum tasks to process per poll.
     */
    private int batchSize = 10;

    /**
     * Maximum verification attempts per task.
     */
    private int maxAttempts = 20;

    /**
     * Lock TTL in milliseconds (how long a worker owns a task).
     */
    private long lockTtlMs = 300_000L;

    /**
     * If true, the scheduler will trigger answerGenerationJob
     * when at least one document verification succeeds in a poll.
     */
    private boolean triggerAnswerJobOnSuccess = true;
}
