package uk.gov.hmcts.cp.cdk.clients.documentstatus;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdk.client.document-status")
public record DocumentStatusClientConfig(
        String baseUrl,
        String statusPath
) {
}

