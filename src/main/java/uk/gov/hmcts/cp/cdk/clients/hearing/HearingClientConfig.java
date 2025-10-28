package uk.gov.hmcts.cp.cdk.clients.hearing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdk.client.hearing")
public record HearingClientConfig(
        String baseUrl,
        String cjsCppuidHeader,
        String acceptHeader
) {
}
