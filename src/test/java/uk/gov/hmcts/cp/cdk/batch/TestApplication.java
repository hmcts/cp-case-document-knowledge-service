package uk.gov.hmcts.cp.cdk.batch;

import uk.gov.hmcts.cp.cdk.batch.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.AzureBlobStorageService;
import uk.gov.hmcts.cp.cdk.filters.tracing.TracingFilter;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableBatchProcessing
@EntityScan(basePackages = "uk.gov.hmcts.cp.cdk.domain")
@EnableJpaRepositories(basePackages = "uk.gov.hmcts.cp.cdk.repo")
@ComponentScan(
        basePackages = "uk.gov.hmcts.cp.cdk",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AzureBlobStorageService.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HearingClient.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ProgressionClient.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TracingFilter.class)
        }
)
public class TestApplication {
}
