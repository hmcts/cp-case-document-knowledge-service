package uk.gov.hmcts.cp.cdk.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdk.cqrs")
public record QueryClientProperties(
        String baseUrl,
        String cjsCppuidHeader,
        String acceptHeader
) {
}
