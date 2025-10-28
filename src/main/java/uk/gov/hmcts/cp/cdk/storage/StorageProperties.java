package uk.gov.hmcts.cp.cdk.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdk.storage.azure")
public record StorageProperties(
        String connectionString,
        String container,
        Long copyPollIntervalMs,   // OPTIONAL: defaults to 1000 ms
        Long copyTimeoutSeconds    // OPTIONAL: defaults to 120 s
) {
}
