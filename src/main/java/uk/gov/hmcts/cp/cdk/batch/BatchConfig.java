package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.cdk.query.QueryClientProperties;
import uk.gov.hmcts.cp.cdk.storage.StorageProperties;

@Configuration
@EnableConfigurationProperties({QueryClientProperties.class, StorageProperties.class, QuestionsProperties.class})
public class BatchConfig {
}
