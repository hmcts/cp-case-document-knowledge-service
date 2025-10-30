package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uk.gov.hmcts.cp.cdk.storage.StorageProperties;

/**
 * Shared Batch configuration / infrastructure beans.
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class})
public class BatchConfig {

    @Bean
    public RetryTemplate retryTemplate() {
        final RetryTemplate tpl = new RetryTemplate();
        final SimpleRetryPolicy policy = new SimpleRetryPolicy(3); // 3 attempts by default
        final ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(5_000);
        tpl.setRetryPolicy(policy);
        tpl.setBackOffPolicy(backoff);
        return tpl;
    }

    @Bean(name = "ingestionTaskExecutor")
    public TaskExecutor ingestionTaskExecutor() {
        final ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setThreadNamePrefix("ingestion-");
        exec.setCorePoolSize(8);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(64);
        exec.initialize();
        return exec;
    }
}
