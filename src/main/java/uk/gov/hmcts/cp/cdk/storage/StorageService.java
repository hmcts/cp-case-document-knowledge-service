package uk.gov.hmcts.cp.cdk.storage;

import java.io.InputStream;
import java.util.Map;

public interface StorageService {

    String upload(String blobPath, InputStream data, long size, String contentType);

    String copyFromUrl(String sourceUrl, String destBlobPath, String contentType, Map<String, String> metadata);

    boolean exists(String blobPath);


    long getBlobSize(final String blobPath);


}
