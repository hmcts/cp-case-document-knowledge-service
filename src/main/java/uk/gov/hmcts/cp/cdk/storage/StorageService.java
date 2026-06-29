package uk.gov.hmcts.cp.cdk.storage;

public interface StorageService {

    boolean exists(String blobPath);

    long getBlobSize(String blobPath);

    DocumentBlobMetadata copyFromUrl(String sourceUrl, String destinationUrl);
}
