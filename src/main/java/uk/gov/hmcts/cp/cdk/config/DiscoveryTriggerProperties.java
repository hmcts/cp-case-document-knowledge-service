package uk.gov.hmcts.cp.cdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds cdk.discovery-trigger.* properties.
 */
@ConfigurationProperties(prefix = "cdk.discovery-trigger")
public class DiscoveryTriggerProperties {

    private int queueCapacity = 10;
    private int awaitTerminationSeconds = 30;

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(final int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getAwaitTerminationSeconds() {
        return awaitTerminationSeconds;
    }

    public void setAwaitTerminationSeconds(final int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }
}
