package uk.gov.hmcts.cp.cdk.batch.storage;

import java.io.InputStream;
import java.util.Map;

public interface StorageService {

    String copyFromUrl(String sourceUrl, String destBlobPath, String contentType, Map<String, String> metadata);

    boolean exists(String blobPath);

    long getBlobSize(String blobPath);

}
