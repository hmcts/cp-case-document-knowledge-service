package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;


@Configuration
@EnableBatchProcessing
public class BatchInfraConfig extends DefaultBatchConfiguration {

}

