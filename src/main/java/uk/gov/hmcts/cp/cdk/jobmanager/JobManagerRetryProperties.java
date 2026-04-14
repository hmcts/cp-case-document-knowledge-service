package uk.gov.hmcts.cp.cdk.jobmanager;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cdk.jobmanager.retry")
public class JobManagerRetryProperties {

    private RetryConfig defaultRetry = new RetryConfig();
    private RetryConfig verifyDocumentStatus = new RetryConfig();
    private RetryConfig questionsRetry = new RetryConfig();

    public RetryConfig getDefaultRetry() {
        return defaultRetry;
    }

    public void setDefaultRetry(final RetryConfig defaultRetry) {
        this.defaultRetry = defaultRetry;
    }

    public RetryConfig getVerifyDocumentStatus() {
        return verifyDocumentStatus;
    }

    public void setVerifyDocumentStatus(final RetryConfig verifyDocumentStatus) {
        this.verifyDocumentStatus = verifyDocumentStatus;
    }

    public RetryConfig getQuestionsRetry() {
        return questionsRetry;
    }

    public void setQuestionsRetry(final RetryConfig questionsRetry) {
        this.questionsRetry = questionsRetry;
    }

    public static class RetryConfig {
        private int maxAttempts = 3;
        private long delaySeconds = 20;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(final int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getDelaySeconds() {
            return delaySeconds;
        }

        public void setDelaySeconds(final long delaySeconds) {
            this.delaySeconds = delaySeconds;
        }
    }
}
