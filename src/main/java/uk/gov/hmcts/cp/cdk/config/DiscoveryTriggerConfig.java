package uk.gov.hmcts.cp.cdk.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated, bounded executor for the manual discovery trigger (Story 3, DD-43062).
 * Not ShedLockConfig -- unrelated to scheduler locking. corePoolSize/maxPoolSize are fixed
 * at 1: boundedness is the only back-pressure, per pod, not a distributed guarantee.
 */
@Configuration
@EnableConfigurationProperties(DiscoveryTriggerProperties.class)
public class DiscoveryTriggerConfig {

    @Bean("discoveryTriggerExecutor")
    public ThreadPoolTaskExecutor discoveryTriggerExecutor(final DiscoveryTriggerProperties properties) {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("discovery-trigger-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }
}
