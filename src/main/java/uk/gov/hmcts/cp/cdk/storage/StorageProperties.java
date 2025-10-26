package uk.gov.hmcts.cp.cdk.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdk.storage.azure")
public record StorageProperties(String connectionString, String container) {
}
