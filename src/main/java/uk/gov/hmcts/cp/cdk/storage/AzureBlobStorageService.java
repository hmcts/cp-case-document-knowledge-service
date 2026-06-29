package uk.gov.hmcts.cp.cdk.storage;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.options.BlobBeginCopyOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AzureBlobStorageService implements StorageService {

    private final BlobContainerClient blobContainerClient;
    private final long pollIntervalMs;
    private final long timeoutSeconds;

    public AzureBlobStorageService(final BlobContainerClient blobContainerClient, final StorageProperties storageProperties) {
        this.blobContainerClient = requireNonNull(blobContainerClient, "blobContainerClient");
        this.pollIntervalMs = storageProperties.copyPollIntervalMs() != null ? storageProperties.copyPollIntervalMs() : 1_000L;
        this.timeoutSeconds = storageProperties.copyTimeoutSeconds() != null ? storageProperties.copyTimeoutSeconds() : 120L;
    }

    @Override
    public DocumentBlobMetadata copyFromUrl(final String sourceUrl, final String destinationUrl) {

        final String blobName = normalizeToBlobName(destinationUrl);
        final BlobClient destinationBlobClient = new BlobClientBuilder()
                .endpoint(destinationUrl)
                .buildClient();

        final BlobBeginCopyOptions copyOptions = new BlobBeginCopyOptions(sourceUrl)
                .setDestinationRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"))
                .setPollInterval(Duration.ofMillis(pollIntervalMs));

        try {
            final SyncPoller<BlobCopyInfo, Void> syncPoller = destinationBlobClient.beginCopy(copyOptions);
            final BlobCopyInfo blobCopyInfo = syncPoller.waitForCompletion(Duration.ofSeconds(timeoutSeconds)).getValue();
            final CopyStatusType copyStatus = blobCopyInfo.getCopyStatus();

            log.info("Blob copy from source to destination completed with status {}. blob={}", copyStatus, blobName);

            if (copyStatus == CopyStatusType.ABORTED || copyStatus == CopyStatusType.FAILED) {
                throw new IllegalStateException("Blob copy from source to destination failed: " + copyStatus);
            }

            final String blobUrl = destinationBlobClient.getBlobUrl();
            log.info("Azure copy from source to destination successful. blob={}, url={}", blobName, blobUrl);
            return new DocumentBlobMetadata(blobUrl, blobName, destinationBlobClient.getProperties().getBlobSize());

        } catch (final RuntimeException runtimeException) {
            if (runtimeException.getCause() instanceof TimeoutException) {
                final String message = "Timed out after " + timeoutSeconds + "s waiting for blob copy to succeed";
                log.error("Timeout error - {} . blob={}", message, blobName);
                throw new IllegalStateException(message);
            }
            log.error("Unexpected error during blob copy. blob={}", blobName, runtimeException);
            throw runtimeException;
        }
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
        } else {
            final String normalized = pathOrUrl.replaceFirst("^/", "");
            log.info("Normalized path to blob name. path={}, normalized={}", pathOrUrl, normalized);
            return normalized;
        }
    }

    private boolean isHttpUrl(final String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }
}
