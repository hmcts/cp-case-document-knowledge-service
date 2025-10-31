package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds cdk.ingestion.* properties.
 */
@ConfigurationProperties(prefix = "cdk.ingestion")
public class IngestionProperties {

    private int corePoolSize = 8;
    private int maxPoolSize = 16;
    private int queueCapacity = 64;
    private final Retry retry = new Retry();

    public static class Retry {
        private int maxAttempts = 10;
        private final Backoff backoff = new Backoff();

        public static class Backoff {
            private long initialMs = 1000;
            private long maxMs = 15000;

            public long getInitialMs() {
                return initialMs;
            }

            public void setInitialMs(final long initialMs) {
                this.initialMs = initialMs;
            }

            public long getMaxMs() {
                return maxMs;
            }

            public void setMaxMs(final long maxMs) {
                this.maxMs = maxMs;
            }
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(final int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Backoff getBackoff() {
            return backoff;
        }
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(final int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(final int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(final int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Retry getRetry() {
        return retry;
    }
}
