package uk.gov.hmcts.cp.cdk.clients.progression;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProgressionClientConfig.class)
public class ProgressionConfig {
}