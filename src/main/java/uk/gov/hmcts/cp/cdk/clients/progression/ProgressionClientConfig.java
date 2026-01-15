package uk.gov.hmcts.cp.cdk.clients.progression;


import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "cqrs.client.progression")
public record ProgressionClientConfig(
        String acceptHeader,
        String docTypeId,
        String courtDocsPath,
        String materialContentPath,
        String acceptForCourtDocSearch,
        String acceptForMaterialContent,
        String acceptForProsecutionCase,
        String prosecutionCasePath

) {
}
