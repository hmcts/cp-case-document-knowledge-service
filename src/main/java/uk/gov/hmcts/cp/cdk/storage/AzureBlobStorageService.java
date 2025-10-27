package uk.gov.hmcts.cp.cdk.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

@Service
public class AzureBlobStorageService implements StorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobContainerClient container;
    private final long pollIntervalMs;
    private final long timeoutSeconds;

    public AzureBlobStorageService(final StorageProperties props) {
        this.container = new BlobContainerClientBuilder()
                .connectionString(Objects.requireNonNull(props.connectionString(), "storage connectionString is required"))
                .containerName(Objects.requireNonNull(props.container(), "storage container is required"))
                .buildClient();
        this.container.createIfNotExists();

        // Poll tuning — defaults if not provided
        this.pollIntervalMs = props.copyPollIntervalMs() != null ? props.copyPollIntervalMs() : 1_000L;
        this.timeoutSeconds = props.copyTimeoutSeconds() != null ? props.copyTimeoutSeconds() : 120L;
    }

    /* default */ AzureBlobStorageService(final BlobContainerClient container) {
        this.container = Objects.requireNonNull(container);
        this.pollIntervalMs = 1_000L;
        this.timeoutSeconds = 120L;
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

    /**
     * Server-side copy. Starts a copy from the source URL into the destination blob,
     * polls until status is SUCCESS/FAILED/ABORTED, applies the desired Content-Type,
     * and returns the destination blob URL.
     */
    @Override
    public String copyFromUrl(final String sourceUrl, final String destBlobPath, final String contentType) {
        final BlobClient blob = container.getBlobClient(destBlobPath);
        final BlockBlobClient block = blob.getBlockBlobClient();

        // Start server-side copy (overwrite if exists)
        // Note: copyFromUrl returns copyId in newer SDKs; older may return void. We don't need the ID for polling.
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

        // Apply Content-Type (if provided; else default)
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
