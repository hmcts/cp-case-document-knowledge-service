package uk.gov.hmcts.cp.cdk.clients.progression;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@ConfigurationProperties(prefix = "cdk.client.progression")
public record ProgressionClientConfig(
        String baseUrl,
        String cjsCppuidHeader,
        String acceptHeader,
        String docTypeId,
        String courtDocsPath,
        String materialContentPath,
        String acceptForCourtDocSearch,
        String acceptForMaterialContent
) {
}
