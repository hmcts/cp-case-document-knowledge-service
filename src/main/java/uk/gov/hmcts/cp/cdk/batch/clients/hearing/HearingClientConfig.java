package uk.gov.hmcts.cp.cdk.batch.clients.hearing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cqrs.client.hearing")
public record HearingClientConfig(
        String acceptHeader,
        String hearingsPath
) {
}
