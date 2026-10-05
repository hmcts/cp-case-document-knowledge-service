package uk.gov.hmcts.cp.cdk.scheduler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {

    private final IntradayDiscovery intradayDiscovery = new IntradayDiscovery();
    private final NightlyDiscovery nightlyDiscovery = new NightlyDiscovery();

    public IntradayDiscovery getIntradayDiscovery() {
        return intradayDiscovery;
    }

    public NightlyDiscovery getNightlyDiscovery() {
        return nightlyDiscovery;
    }

    @Data
    public static class IntradayDiscovery {
        private String name;
        private String cron;
        private String lockAtLeastFor;
        private String lockAtMostFor;

        /**
         * Mirrors @ConditionalOnProperty(..., havingValue = "true", matchIfMissing = true) on
         * IntradayDiscoveryScheduler. The Java default is deliberately true so that "property
         * absent" resolves the same way here as it does in the conditional. application-cdk.yml
         * always supplies a value (defaulting to false via CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED),
         * so the effective shipped default is unchanged. If the conditional's matchIfMissing is
         * ever changed, change this default with it.
         */
        private boolean enabled = true;
    }

    @Data
    public static class NightlyDiscovery {
        private String name;
        private String cron;
        private String lockAtLeastFor;
        private String lockAtMostFor;
        private int daysAhead = 3;

        /**
         * Mirrors @ConditionalOnProperty(..., havingValue = "true", matchIfMissing = true) on
         * NightlyDiscoveryScheduler. See IntradayDiscovery#enabled for the same rationale.
         */
        private boolean enabled = true;
    }
}
