package uk.gov.hmcts.cp.cdk.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdk.storage.azure")
public record StorageProperties(
        String mode,                     // connection-string | managed-identity | azurite
        String connectionString,
        String container,
        Long copyPollIntervalMs,
        Long copyTimeoutSeconds,

        // Managed Identity fields
        String accountName,
        String blobEndpoint,
        String managedIdentityClientId,

        // Azurite
        Azurite azurite
) {
    public record Azurite(String image) {
    }
}
