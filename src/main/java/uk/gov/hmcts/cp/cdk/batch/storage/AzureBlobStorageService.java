package uk.gov.hmcts.cp.cdk.batch.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.specialized.BlockBlobClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@Service
@ConditionalOnMissingBean(StorageService.class)
@Slf4j
public class AzureBlobStorageService implements StorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobContainerClient blobContainerClient;
    private final long pollIntervalMs;
    private final long timeoutSeconds;

    public AzureBlobStorageService(final StorageProperties storageProperties) {
        requireNonNull(storageProperties, "storageProperties");
        final String conn = requireNonNull(storageProperties.connectionString(), "storage connectionString is required");
        final String container = requireNonNull(storageProperties.container(), "storage container is required");

        this.blobContainerClient = new BlobContainerClientBuilder()
                .connectionString(conn)
                .containerName(container)
                .buildClient();

        try {
            this.blobContainerClient.createIfNotExists();
        } catch (RuntimeException e) {
            log.warn("createIfNotExists failed (continuing, may already exist): {}", container, e);
        }

        this.pollIntervalMs = storageProperties.copyPollIntervalMs() != null
                ? storageProperties.copyPollIntervalMs() : 1_000L;
        this.timeoutSeconds = storageProperties.copyTimeoutSeconds() != null
                ? storageProperties.copyTimeoutSeconds() : 120L;
    }

    /* package */ AzureBlobStorageService(final BlobContainerClient blobContainerClient) {
        this.blobContainerClient = requireNonNull(blobContainerClient, "blobContainerClient");
        this.pollIntervalMs = 1_000L;
        this.timeoutSeconds = 120L;
    }

    @Override
    public String upload(final String blobPath, final InputStream data, final long size, final String contentType) {
        final String blobName = normalizeToBlobName(blobPath);
        if (data == null) throw new IllegalArgumentException("data must not be null");
        if (size < 0) throw new IllegalArgumentException("size must be >= 0");

        final BlobClient blob = blobContainerClient.getBlobClient(blobName);
        blob.upload(data, size, true);

        final String ct = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        try {
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType(ct));
        } catch (RuntimeException ex) {
            log.warn("Failed to set content-type on upload (continuing). name={}, type={}", blobName, ct, ex);
        }
        return blob.getBlobUrl();
    }

    @Override
    public String copyFromUrl(final String sourceUrl,
                              final String destBlobPath,
                              final String contentType,
                              final Map<String, String> metadata) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl must not be blank");
        }
        final String blobName = normalizeToBlobName(destBlobPath);
        final BlobClient blob = blobContainerClient.getBlobClient(blobName);
        final BlockBlobClient block = blob.getBlockBlobClient();

        try {
            blob.deleteIfExists();
        } catch (RuntimeException ex) {
            log.warn("deleteIfExists failed (continuing). name={}", blobName, ex);
        }

        final String copyId;
        try {
            copyId = block.copyFromUrl(sourceUrl);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to start copyFromUrl: " + sourceUrl, ex);
        }

        final long deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        CopyStatusType status = null;
        String statusDesc = null;

        sleepQuiet(Math.min(pollIntervalMs, 250));

        while (System.nanoTime() < deadlineNanos) {
            final BlobProperties props = blob.getProperties();
            status = props.getCopyStatus();
            statusDesc = props.getCopyStatusDescription();

            if (status == CopyStatusType.SUCCESS) break;
            if (status == CopyStatusType.ABORTED || status == CopyStatusType.FAILED) {
                throw new IllegalStateException("Blob copy failed: " + (statusDesc == null ? status : statusDesc));
            }
            sleepQuiet(pollIntervalMs);
        }

        if (status != CopyStatusType.SUCCESS) {
            try {
                blob.abortCopyFromUrl(copyId);
            } catch (Exception ignore) { /* ignore */ }
            throw new IllegalStateException("Timed out after " + timeoutSeconds + "s waiting for blob copy to succeed");
        }

        final String ct = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        try {
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType(ct));
        } catch (RuntimeException ex) {
            log.warn("Failed to set content-type on copied blob (continuing). name={}, type={}", blobName, ct, ex);
        }

        if (metadata != null && !metadata.isEmpty()) {
            try {
                blob.setMetadata(normalizeMetadataKeys(metadata));
            } catch (RuntimeException ex) {
                log.warn("Failed to set metadata on copied blob (continuing). name={}, keys={}", blobName, metadata.keySet(), ex);
            }
        }

        return blob.getBlobUrl();
    }

    @Override
    public boolean exists(final String blobPath) {
        final String blobName = normalizeToBlobName(blobPath);
        return blobContainerClient.getBlobClient(blobName).exists();
    }

    @Override
    public long getBlobSize(final String blobPath) {
        final String blobName = normalizeToBlobName(blobPath);
        final BlobClient client = blobContainerClient.getBlobClient(blobName);
        if (!client.exists()) {
            throw new IllegalStateException("Blob not found: " + blobName);
        }
        return client.getProperties().getBlobSize();
    }

    /* -------------------- helpers -------------------- */

    private String normalizeToBlobName(final String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            throw new IllegalArgumentException("blob path/url must not be blank");
        }

        if (isHttpUrl(pathOrUrl)) {
            final URI uri = URI.create(pathOrUrl);
            final String rawPath = uri.getRawPath();
            final String container = blobContainerClient.getBlobContainerName();
            final String prefix = "/" + container + "/";
            String withinContainer = rawPath.startsWith(prefix)
                    ? rawPath.substring(prefix.length())
                    : rawPath.replaceFirst("^/", "");
            withinContainer = URLDecoder.decode(withinContainer, StandardCharsets.UTF_8);
            return withinContainer.replaceFirst("^/", "");
        }

        return pathOrUrl.replaceFirst("^/", "");
    }

    private static boolean isHttpUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static Map<String, String> normalizeMetadataKeys(Map<String, String> metadata) {
        final Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            final String k = (e.getKey() == null) ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            normalized.put(k, e.getValue());
        }
        return normalized;
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for blob operation", ie);
        }
    }
}
