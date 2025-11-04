package uk.gov.hmcts.cp.cdk.batch.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdk.ingestion.upload")
public record UploadProperties(
        String blobPrefix,
        String datePattern,
        String fileExtension,
        String contentType
) {
    public UploadProperties {
        if (blobPrefix == null || blobPrefix.isBlank()) {
            blobPrefix = "cases";
        }
        if (datePattern == null || datePattern.isBlank()) {
            datePattern = "yyyyMMdd";
        }
        if (fileExtension == null || fileExtension.isBlank()) {
            fileExtension = ".pdf";
        }
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/pdf";
        }
    }
}
