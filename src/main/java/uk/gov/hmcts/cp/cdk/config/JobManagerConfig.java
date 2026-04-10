package uk.gov.hmcts.cp.cdk.config;

import static java.util.Objects.requireNonNull;

import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.storage.AzureBlobStorageService;
import uk.gov.hmcts.cp.cdk.storage.StorageProperties;
import uk.gov.hmcts.cp.cdk.storage.StorageService;
import uk.gov.hmcts.cp.cdk.storage.UploadProperties;

import java.util.Locale;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;


/**
 * Core infrastructure configuration:
 * - Azure Blob Storage client wiring
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        StorageProperties.class,
        UploadProperties.class,
        IngestionProperties.class,
        VerifySchedulerProperties.class
})
public class JobManagerConfig {

    private static final String CONNECTION_STRING_MODE = "connection-string";
    private static final String MANAGED_IDENTITY_MODE = "managed-identity";

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    @Bean
    @ConditionalOnMissingBean
    public BlobContainerClient blobContainerClient(final StorageProperties storageProperties) {
        final String mode = StringUtils
                .defaultIfBlank(storageProperties.mode(), CONNECTION_STRING_MODE)
                .toLowerCase(Locale.ROOT);

        final String containerName = requireNonNull(
                storageProperties.container(),
                "cdk.storage.azure.container is required"
        );

        return switch (mode) {
            case CONNECTION_STRING_MODE -> {
                final String connectionString = requireNonNull(
                        storageProperties.connectionString(),
                        "cdk.storage.azure.connection-string is required for connection-string mode"
                );
                final BlobContainerClient blobContainerClient = new BlobContainerClientBuilder()
                        .connectionString(connectionString)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(blobContainerClient, containerName);
                yield blobContainerClient;
            }
            case MANAGED_IDENTITY_MODE -> {
                String endpoint = StringUtils.trimToNull(storageProperties.blobEndpoint());
                if (endpoint == null) {
                    final String accountName = requireNonNull(
                            storageProperties.accountName(),
                            "cdk.storage.azure.account-name is required when blob-endpoint is not provided"
                    );
                    endpoint = "https://" + accountName + ".blob.core.windows.net";
                }

                final String userAssignedClientId =
                        StringUtils.trimToNull(storageProperties.managedIdentityClientId());

                final TokenCredential tokenCredential =
                        userAssignedClientId != null
                                ? new ManagedIdentityCredentialBuilder().clientId(userAssignedClientId).build()
                                : new DefaultAzureCredentialBuilder().build();

                final BlobContainerClient blobContainerClient = new BlobContainerClientBuilder()
                        .endpoint(endpoint)
                        .credential(tokenCredential)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(blobContainerClient, containerName);
                yield blobContainerClient;
            }
            default ->
                    throw new IllegalArgumentException("Unsupported cdk.storage.azure.mode: " + mode);
        };
    }

    private void createContainerIfMissing(final BlobContainerClient blobContainerClient,
                                          final String containerName) {
        try {
            blobContainerClient.createIfNotExists();
        } catch (final RuntimeException runtimeException) {
            log.warn(
                    "Container creation skipped or failed. container={} reason={}",
                    containerName,
                    runtimeException.getMessage()
            );
        }
    }

    @Bean
    public StorageService storageService(final BlobContainerClient blobContainerClient,
                                         final StorageProperties storageProperties) {
        return new AzureBlobStorageService(blobContainerClient, storageProperties);
    }
}
