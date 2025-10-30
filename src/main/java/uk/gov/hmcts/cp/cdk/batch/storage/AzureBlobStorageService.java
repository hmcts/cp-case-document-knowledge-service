package uk.gov.hmcts.cp.cdk.batch.storage;

import com.azure.storage.blob.*;
import com.azure.storage.blob.models.*;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@Service
@ConditionalOnMissingBean(StorageService.class)
public class AzureBlobStorageService implements StorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobContainerClient blobContainerClient;
    private final long pollIntervalMs;
    private final long timeoutSeconds;

    @Autowired // <-- disambiguate when there are multiple ctors
    public AzureBlobStorageService(final StorageProperties storageProperties) {
        this.blobContainerClient = new BlobContainerClientBuilder()
                .connectionString(requireNonNull(storageProperties.connectionString(), "storage connectionString is required"))
                .containerName(requireNonNull(storageProperties.container(), "storage container is required"))
                .buildClient();
        this.blobContainerClient.createIfNotExists();

        this.pollIntervalMs = storageProperties.copyPollIntervalMs() != null ? storageProperties.copyPollIntervalMs() : 1_000L;
        this.timeoutSeconds = storageProperties.copyTimeoutSeconds() != null ? storageProperties.copyTimeoutSeconds() : 120L;
    }

    // test-only helper; Spring will ignore it because the other ctor is @Autowired
    /* default */ AzureBlobStorageService(final BlobContainerClient blobContainerClient) {
        this.blobContainerClient = requireNonNull(blobContainerClient);
        this.pollIntervalMs = 1_000L;
        this.timeoutSeconds = 120L;
    }

    @Override
    public String upload(final String blobPath, final InputStream data, final long size, final String contentType) {
        final BlobClient blob = blobContainerClient.getBlobClient(blobPath);
        blob.upload(data, size, true);
        final String ct = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(ct));
        return blob.getBlobUrl();
    }

    @Override
    public String copyFromUrl(final String sourceUrl,
                              final String destBlobPath,
                              final String contentType,
                              final Map<String, String> metadata) {
        final BlobClient blob = blobContainerClient.getBlobClient(destBlobPath);
        final BlockBlobClient block = blob.getBlockBlobClient();
        block.copyFromUrl(sourceUrl);

        final long deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        CopyStatusType status;
        do {
            final BlobProperties props = blob.getProperties();
            status = props.getCopyStatus();

            if (status == CopyStatusType.SUCCESS) break;
            if (status == CopyStatusType.ABORTED || status == CopyStatusType.FAILED) {
                final String desc = props.getCopyStatusDescription();
                throw new IllegalStateException("Blob copy failed: " + (desc == null ? status : desc));
            }
            try { Thread.sleep(pollIntervalMs); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for blob copy to complete", ie);
            }
        } while (System.nanoTime() < deadlineNanos);

        if (status != CopyStatusType.SUCCESS) {
            throw new IllegalStateException("Timed out after " + timeoutSeconds + "s waiting for blob copy to succeed");
        }

        final String ct = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(ct));
        if (metadata != null && !metadata.isEmpty()) {
            blob.setMetadata(metadata);
        }
        return blob.getBlobUrl();
    }

    @Override
    public boolean exists(final String blobPath) {
        return blobContainerClient.getBlobClient(blobPath).exists();
    }

    @Override
    public long getBlobSize(final String blobPath) {
        return blobContainerClient.getBlobClient(blobPath).getProperties().getBlobSize();
    }
}
