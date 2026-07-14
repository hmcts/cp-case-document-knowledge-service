package uk.gov.hmcts.cp.cdk.clients.hearing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cqrs.client.hearing")
public record HearingClientConfig(
        String getHearingsAcceptHeader,
        String getHearingsPath,
        String getHearingCasesForDayAcceptHeader,
        String getHearingCasesForDayPath
) {
}
