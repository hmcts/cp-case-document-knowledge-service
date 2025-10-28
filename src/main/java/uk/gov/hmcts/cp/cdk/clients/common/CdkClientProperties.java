package uk.gov.hmcts.cp.cdk.clients.common;


import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "cdk.client")
public record CdkClientProperties(
        String baseUrl,
        Headers headers
) {
    public record Headers(
            String cjsCppuid
    ) {
    }
}