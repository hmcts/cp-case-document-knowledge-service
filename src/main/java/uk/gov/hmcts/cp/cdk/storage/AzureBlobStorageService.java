package uk.gov.hmcts.cp.cdk.storage;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.springframework.stereotype.Service;

@Service
public class AzureBlobStorageService implements StorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobContainerClient blobContainerClient;
    private final long pollIntervalMs;
    private final long timeoutSeconds;

    public AzureBlobStorageService(final StorageProperties storageProperties) {
        this.blobContainerClient = new BlobContainerClientBuilder()
                // Todo change this to managed identity
                .connectionString(requireNonNull(storageProperties.connectionString(), "storage connectionString is required"))
                .containerName(requireNonNull(storageProperties.container(), "storage container is required"))
                .buildClient();
        this.blobContainerClient.createIfNotExists();

        // Poll tuning — defaults if not provided
        this.pollIntervalMs = storageProperties.copyPollIntervalMs() != null ? storageProperties.copyPollIntervalMs() : 1_000L;
        this.timeoutSeconds = storageProperties.copyTimeoutSeconds() != null ? storageProperties.copyTimeoutSeconds() : 120L;
    }

    /* default */ AzureBlobStorageService(final BlobContainerClient blobContainerClient) {
        this.blobContainerClient = requireNonNull(blobContainerClient);
        this.pollIntervalMs = 1_000L;
        this.timeoutSeconds = 120L;
    }

    @Override
    public String upload(final String blobPath, final InputStream data, final long size, final String contentType) {
        final BlobClient blob = blobContainerClient.getBlobClient(blobPath);
        blob.upload(data, size, true);
        final String contentTypeToApply =
                (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentTypeToApply));
        return blob.getBlobUrl();
    }

    public String copyFromUrl(final String sourceUrl,
                              final String destBlobPath,
                              final String contentType,
                              final Map<String, String> metadata) {
        final BlobClient blob = blobContainerClient.getBlobClient(destBlobPath);
        final BlockBlobClient block = blob.getBlockBlobClient();

        // Start server-side copy (overwrite if exists)
        block.copyFromUrl(sourceUrl);

        // Poll copy status
        final long deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        CopyStatusType status;
        do {
            final BlobProperties props = blob.getProperties();
            status = props.getCopyStatus();

            if (status == CopyStatusType.SUCCESS) {
                break;
            }
            if (status == CopyStatusType.ABORTED || status == CopyStatusType.FAILED) {
                final String statusDesc = props.getCopyStatusDescription();
                throw new IllegalStateException("Blob copy failed: " + (statusDesc == null ? status : statusDesc));
            }

            // Pending — sleep and retry
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for blob copy to complete", ie);
            }
        } while (System.nanoTime() < deadlineNanos);

        // Timeout guard
        if (status != CopyStatusType.SUCCESS) {
            throw new IllegalStateException("Timed out after " + timeoutSeconds + "s waiting for blob copy to succeed");
        }

        // Apply Content-Type
        final String contentTypeToApply =
                (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentTypeToApply));

        // Set metadata after copy completes (Azure Java SDK doesn't support setting metadata during copy)
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
        final BlobClient blob = blobContainerClient.getBlobClient(blobPath);
        final BlobProperties props = blob.getProperties();
        return props.getBlobSize();
    }


}
