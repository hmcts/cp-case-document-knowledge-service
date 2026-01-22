package uk.gov.hmcts.cp.cdk.storage;

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
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.options.BlobBeginCopyOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AzureBlobStorageService implements StorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobContainerClient blobContainerClient;
    private final long pollIntervalMs;
    private final long timeoutSeconds;

    public AzureBlobStorageService(final BlobContainerClient blobContainerClient, final StorageProperties storageProperties) {
        this.blobContainerClient = requireNonNull(blobContainerClient, "blobContainerClient");
        this.pollIntervalMs = storageProperties.copyPollIntervalMs() != null ? storageProperties.copyPollIntervalMs() : 1_000L;
        this.timeoutSeconds = storageProperties.copyTimeoutSeconds() != null ? storageProperties.copyTimeoutSeconds() : 120L;
    }

    private static Map<String, String> normalizeMetadataKeys(final Map<String, String> metadata) {
        final Map<String, String> normalized = new HashMap<>();
        if (metadata != null) {
            for (final Map.Entry<String, String> entry : metadata.entrySet()) {
                final String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
                normalized.put(key, entry.getValue());
            }
        }
        return normalized;
    }

    @Override
    public String copyFromUrl(final String sourceUrl,
                              final String destBlobPath,
                              final Map<String, String> metadata) {
        if (StringUtils.isBlank(sourceUrl)) {
            throw new IllegalArgumentException("sourceUrl must not be blank");
        }
        String blobUrl = "";
        final String blobName = normalizeToBlobName(destBlobPath);
        final BlobClient blobClient = blobContainerClient.getBlobClient(blobName);

        log.info("Starting server-side blob copy. container={}, blob={}, pollIntervalMs={}, timeoutSeconds={}",
                blobContainerClient.getBlobContainerName(), blobName, pollIntervalMs, timeoutSeconds);
        final Map<String, String> normalizedMetadata =
                MapUtils.isNotEmpty(metadata) ? normalizeMetadataKeys(metadata) : Map.of();

        final BlobBeginCopyOptions copyOptions =
                new BlobBeginCopyOptions(sourceUrl).setPollInterval(Duration.ofMillis(pollIntervalMs));

        if (MapUtils.isNotEmpty(normalizedMetadata)) {
            copyOptions.setMetadata(normalizedMetadata);
            log.info("Applying metadata on copy. blob={}, metadataKeys={}", blobName, normalizedMetadata.keySet());
        }
        try {

            final boolean existsFlag = blobClient.exists();
            if (existsFlag) {
                log.debug("Blob already exists before copy. blob={}", blobName);
            } else {
                try {

                    final SyncPoller<BlobCopyInfo, Void> syncPoller = blobClient.beginCopy(copyOptions);
                    final BlobCopyInfo blobCopyInfo = syncPoller.waitForCompletion(Duration.ofSeconds(timeoutSeconds)).getValue();
                    final CopyStatusType copyStatus = blobCopyInfo.getCopyStatus();

                    log.info("Blob copy completed with status {}. blob={}", copyStatus, blobName);

                    if (copyStatus == CopyStatusType.ABORTED || copyStatus == CopyStatusType.FAILED) {
                        throw new IllegalStateException("Blob copy failed: " + copyStatus);
                    }

                    blobUrl = blobClient.getBlobUrl();
                    log.info("Server-side copy successful. blob={}, url={}", blobName, blobUrl);
                    return blobUrl;

                } catch (final RuntimeException runtimeException) {
                    if (runtimeException.getCause() instanceof TimeoutException) {
                        final String message = "Timed out after " + timeoutSeconds + "s waiting for blob copy to succeed";
                        log.error("{} . blob={}", message, blobName);
                        throw new IllegalStateException(message);
                    }
                    log.error("Unexpected error during blob copy. blob={}", blobName, runtimeException);
                    throw runtimeException;
                }

            }
        } catch (final RuntimeException existsException) {
            log.warn("exists check failed . blob={}", blobName, existsException);
            throw existsException;
        }

        return blobUrl;
    }

    @Override
    public boolean exists(final String blobPath) {
        final String blobName = normalizeToBlobName(blobPath);
        final boolean exists = blobContainerClient.getBlobClient(blobName).exists();
        log.info("Blob exists check. blob={}, exists={}", blobName, exists);
        return exists;
    }

    @Override
    public long getBlobSize(final String blobPath) {
        final String blobName = normalizeToBlobName(blobPath);
        final BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
        if (!blobClient.exists()) {
            log.warn("Blob not found when getting size. blob={}", blobName);
            throw new IllegalStateException("Blob not found: " + blobClient.getBlobName());
        }
        final long size = blobClient.getProperties().getBlobSize();
        log.info("Blob size fetched. blob={}, size={}", blobName, size);
        return size;
    }

    private String normalizeToBlobName(final String pathOrUrl) {
        if (StringUtils.isBlank(pathOrUrl)) {
            throw new IllegalArgumentException("blob path/url must not be blank");
        }
        if (isHttpUrl(pathOrUrl)) {
            final URI uri = URI.create(pathOrUrl);
            final String rawPath = uri.getRawPath();
            final String containerName = blobContainerClient.getBlobContainerName();
            final String containerPrefix = "/" + containerName + "/";
            String withinContainer = rawPath.startsWith(containerPrefix)
                    ? rawPath.substring(containerPrefix.length())
                    : rawPath.replaceFirst("^/", "");
            withinContainer = URLDecoder.decode(withinContainer, StandardCharsets.UTF_8);
            final String normalized = withinContainer.replaceFirst("^/", "");
            log.info("Normalized URL to blob name. url={}, normalized={}", pathOrUrl, normalized);
            return normalized;
        }

        final String normalized = pathOrUrl.replaceFirst("^/", "");
        log.info("Normalized path to blob name. path={}, normalized={}", pathOrUrl, normalized);
        return normalized;
    }

    private boolean isHttpUrl(final String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }
}
