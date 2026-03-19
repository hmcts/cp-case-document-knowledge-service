package uk.gov.hmcts.cp.cdk.storage;

public record DocumentBlobMetadata(
        String blobUrl,
        String blobName,
        long blobSize
) {
}
