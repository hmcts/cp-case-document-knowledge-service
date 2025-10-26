package uk.gov.hmcts.cp.cdk.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Objects;

@Service
public class AzureBlobStorageService implements StorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobContainerClient container;

    public AzureBlobStorageService(final StorageProperties props) {
        this.container = new BlobContainerClientBuilder()
                .connectionString(Objects.requireNonNull(props.connectionString()))
                .containerName(Objects.requireNonNull(props.container()))
                .buildClient();
        this.container.createIfNotExists();
    }

    /* default */ AzureBlobStorageService(final BlobContainerClient container) {
        this.container = Objects.requireNonNull(container);
    }

    @Override
    public String upload(final String blobPath, final InputStream data, final long size, final String contentType) {
        final BlobClient blob = container.getBlobClient(blobPath);
        blob.upload(data, size, true);
        final String contentTypeToApply =
                (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentTypeToApply));
        return blob.getBlobUrl();
    }

    @Override
    public String copyFromUrl(final String sourceUrl, final String destBlobPath, final String contentType) {
        final BlobClient blob = container.getBlobClient(destBlobPath);
        final BlockBlobClient block = blob.getBlockBlobClient();
        block.copyFromUrl(sourceUrl);
        final String contentTypeToApply =
                (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentTypeToApply));
        return blob.getBlobUrl();
    }

    @Override
    public boolean exists(final String blobPath) {
        return container.getBlobClient(blobPath).exists();
    }
}
