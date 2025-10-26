package uk.gov.hmcts.cp.cdk.storage;

import java.io.InputStream;

public interface StorageService {

    String upload(String blobPath, InputStream data, long size, String contentType);

    String copyFromUrl(String sourceUrl, String destBlobPath, String contentType);

    boolean exists(String blobPath);

}
