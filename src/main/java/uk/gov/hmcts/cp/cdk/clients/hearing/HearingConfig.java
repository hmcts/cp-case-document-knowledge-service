package uk.gov.hmcts.cp.cdk.clients.hearing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HearingClientConfig.class)
public class HearingConfig {
}