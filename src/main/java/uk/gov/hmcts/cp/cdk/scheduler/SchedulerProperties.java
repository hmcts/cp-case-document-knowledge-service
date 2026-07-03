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
    }

    @Data
    public static class NightlyDiscovery {
        private String name;
        private String cron;
        private String lockAtLeastFor;
        private String lockAtMostFor;
        private int daysAhead = 3;
    }
}
