package uk.gov.hmcts.cp.cdk.storage;

import java.util.Map;

public interface StorageService {

    boolean exists(String blobPath);

    long getBlobSize(String blobPath);

    DocumentBlobMetadata copyFromUrl(String sourceUrl, String destinationUrl);
}
