package uk.gov.hmcts.cp.cdk.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uk.gov.hmcts.cp.cdk.batch.storage.AzureBlobStorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageProperties;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.UploadProperties;

@Configuration
@EnableConfigurationProperties({StorageProperties.class, UploadProperties.class, IngestionProperties.class})
public class BatchConfig {

    private final IngestionProperties props;

    public BatchConfig(final IngestionProperties props) {
        this.props = props;
    }

    @Bean
    public RetryTemplate retryTemplate() {
        final RetryTemplate retryTemplate = new RetryTemplate();
        final SimpleRetryPolicy policy = new SimpleRetryPolicy(props.getRetry().getMaxAttempts());
        final ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(props.getRetry().getBackoff().getInitialMs());
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(props.getRetry().getBackoff().getMaxMs());
        retryTemplate.setRetryPolicy(policy);
        retryTemplate.setBackOffPolicy(backoff);
        return retryTemplate;
    }

    @Bean(name = "ingestionTaskExecutor")
    @ConditionalOnMissingBean(name = "ingestionTaskExecutor")
    public TaskExecutor ingestionTaskExecutor() {
        final ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setThreadNamePrefix("ingestion-");
        threadPoolTaskExecutor.setCorePoolSize(props.getCorePoolSize());
        threadPoolTaskExecutor.setMaxPoolSize(props.getMaxPoolSize());
        threadPoolTaskExecutor.setQueueCapacity(props.getQueueCapacity());
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        threadPoolTaskExecutor.setAwaitTerminationSeconds(30);
        threadPoolTaskExecutor.initialize();
        return threadPoolTaskExecutor;
    }

    @Bean
    public ObjectMapper objectMapper() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    @Bean
    public StorageService storageService(final StorageProperties storageProperties) {
        return new AzureBlobStorageService(storageProperties);
    }
}
