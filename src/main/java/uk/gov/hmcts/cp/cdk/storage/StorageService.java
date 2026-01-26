package uk.gov.hmcts.cp.cdk.storage;

import java.util.Map;

public interface StorageService {

    String copyFromUrl(String sourceUrl, String destBlobPath, Map<String, String> metadata);

    boolean exists(String blobPath);

    long getBlobSize(String blobPath);

}
