package uk.gov.hmcts.cp.cdk.batch.storage;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.options.BlobBeginCopyOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

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
    public String copyFromUrl(final String sourceUrl,
                              final String destBlobPath,
                              final String contentType,
                              final Map<String, String> metadata) {
        if (StringUtils.isBlank(sourceUrl)) {
            throw new IllegalArgumentException("sourceUrl must not be blank");
        }
        final String blobName = normalizeToBlobName(destBlobPath);

        final BlobClient blob = blobContainerClient.getBlobClient(blobName);
        try {
            blob.deleteIfExists();
        } catch (RuntimeException ex) {
            log.warn("deleteIfExists failed (continuing). name={}", blobName, ex);
        }

        final Map<String, String> normalizedMetadata = MapUtils.isNotEmpty(metadata) ? normalizeMetadataKeys(metadata) : Map.of();
        final BlobBeginCopyOptions options = new BlobBeginCopyOptions(sourceUrl).setPollInterval(Duration.ofMillis(pollIntervalMs));
        if (MapUtils.isNotEmpty(normalizedMetadata)) {
            options.setMetadata(normalizedMetadata);
        }

        try {
            final SyncPoller<BlobCopyInfo, Void> poller = blob.beginCopy(options);
            final BlobCopyInfo copyInfo = poller.waitForCompletion(Duration.ofSeconds(timeoutSeconds)).getValue();
            final CopyStatusType copyStatus = copyInfo.getCopyStatus();
            if (copyStatus == CopyStatusType.ABORTED || copyStatus == CopyStatusType.FAILED) {
                throw new IllegalStateException("Blob copy failed: " + copyStatus);
            }

            if (copyStatus == CopyStatusType.SUCCESS) {
                final String ctype = StringUtils.defaultIfBlank(contentType, DEFAULT_CONTENT_TYPE);
                try {
                    blob.setHttpHeaders(new BlobHttpHeaders().setContentType(ctype));
                } catch (RuntimeException ex) {
                    log.warn("Failed to set content-type on copied blob (continuing). name={}, type={}", blobName, ctype, ex);
                }
            }

            return blob.getBlobUrl();
        } catch (RuntimeException e) {
            // Sync poller throws this exception if it exceeds the timeout
            if (e.getCause() instanceof TimeoutException) {
                throw new IllegalStateException("Timed out after " + timeoutSeconds + "s waiting for blob copy to succeed");
            }
            throw e;
        }
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
    @SuppressWarnings("PMD.OnlyOneReturn")
    private String normalizeToBlobName(final String pathOrUrl) {
        if (StringUtils.isBlank(pathOrUrl)) {
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

    private boolean isHttpUrl(final String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static Map<String, String> normalizeMetadataKeys(final Map<String, String> metadata) {
        final Map<String, String> normalized = new HashMap<>();
        if (MapUtils.isNotEmpty(metadata)) {
            for (final Map.Entry<String, String> entry : metadata.entrySet()) {
                final String key = (entry.getKey() == null) ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
                normalized.put(key, entry.getValue());
            }
        }
        return normalized;
    }
}
