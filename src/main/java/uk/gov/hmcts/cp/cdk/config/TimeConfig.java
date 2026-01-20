package uk.gov.hmcts.cp.cdk.config;

import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.services.IngestionProcessor;
import uk.gov.hmcts.cp.cdk.services.IngestionService;
import uk.gov.hmcts.cp.cdk.services.JobManagerService;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * Selects the correct IngestionProcessor implementation based on feature flag.
     */
    @Bean
    public IngestionProcessor ingestionProcessor(JobManagerService jobManagerService,
                                                 IngestionService ingestionService,
                                                 IngestionProperties ingestionProperties) {
        if (ingestionProperties.getFeature().isUseJobManager()) {
            return jobManagerService;
        } else {
            return ingestionService;
        }
    }
}
