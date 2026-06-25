package uk.gov.hmcts.cp.cdk.scheduler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {

    private final IntradayDiscovery intradayDiscovery = new IntradayDiscovery();

    public IntradayDiscovery getIntradayDiscovery() {
        return intradayDiscovery;
    }

    @Data
    public static class IntradayDiscovery {
        private String name;
        private String cron;
        private String lockAtLeastFor;
        private String lockAtMostFor;
    }
}
