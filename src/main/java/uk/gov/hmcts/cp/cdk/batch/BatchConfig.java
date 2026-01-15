package uk.gov.hmcts.cp.cdk.batch;

import static java.util.Objects.requireNonNull;

import uk.gov.hmcts.cp.cdk.storage.AzureBlobStorageService;
import uk.gov.hmcts.cp.cdk.storage.StorageProperties;
import uk.gov.hmcts.cp.cdk.storage.StorageService;
import uk.gov.hmcts.cp.cdk.storage.UploadProperties;
import uk.gov.hmcts.cp.cdk.config.VerifySchedulerProperties;

import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Core batch infrastructure configuration:
 * - RetryTemplate for tasklets
 * - TaskExecutors for partitioning
 * - Azure Blob Storage client wiring
 * - Azurite for local / integration tests
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        StorageProperties.class,
        UploadProperties.class,
        IngestionProperties.class,
        PartitioningProperties.class,
        VerifySchedulerProperties.class
})
public class BatchConfig {

    private static final int AZURITE_PORT = 10_000;
    private static final String DEFAULT_AZURITE_IMAGE =
            "mcr.microsoft.com/azure-storage/azurite:3.33.0";
    private static final String DEV_ACCOUNT_NAME = "devstoreaccount1";
    private static final String DEV_ACCOUNT_KEY = "REDACTED";
    private static final String CONNECTION_STRING_MODE = "connection-string";
    private static final String MANAGED_IDENTITY_MODE = "managed-identity";
    private static final String AZURITE_MODE = "azurite";

    private final IngestionProperties ingestionProperties;

    public BatchConfig(final IngestionProperties ingestionProperties) {
        this.ingestionProperties = ingestionProperties;
    }

    @Bean
    public RetryTemplate retryTemplate() {
        final RetryTemplate retryTemplate = new RetryTemplate();

        final SimpleRetryPolicy simpleRetryPolicy =
                new SimpleRetryPolicy(ingestionProperties.getRetry().getMaxAttempts());

        final ExponentialBackOffPolicy exponentialBackOffPolicy = new ExponentialBackOffPolicy();
        exponentialBackOffPolicy.setInitialInterval(
                ingestionProperties.getRetry().getBackoff().getInitialMs());
        exponentialBackOffPolicy.setMultiplier(2.0);
        exponentialBackOffPolicy.setMaxInterval(
                ingestionProperties.getRetry().getBackoff().getMaxMs());

        retryTemplate.setRetryPolicy(simpleRetryPolicy);
        retryTemplate.setBackOffPolicy(exponentialBackOffPolicy);
        return retryTemplate;
    }

    /**
     * Primary executor for ingestion and case-level partitioning.
     * Any unqualified TaskExecutor injection (e.g. in services) will use this.
     */
    @Bean(name = "ingestionTaskExecutor")
    @Primary
    @ConditionalOnMissingBean(name = "ingestionTaskExecutor")
    public TaskExecutor ingestionTaskExecutor() {
        final ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setThreadNamePrefix("ingestion-");
        threadPoolTaskExecutor.setCorePoolSize(ingestionProperties.getCorePoolSize());
        threadPoolTaskExecutor.setMaxPoolSize(ingestionProperties.getMaxPoolSize());
        threadPoolTaskExecutor.setQueueCapacity(ingestionProperties.getQueueCapacity());
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        threadPoolTaskExecutor.setAwaitTerminationSeconds(30);
        threadPoolTaskExecutor.initialize();
        return threadPoolTaskExecutor;
    }

    /**
     * Dedicated executor for query-level partitioning in step 6.
     * Keeps nested partitions from contending with the ingestion pool.
     */
    @Bean(name = "queryPartitionTaskExecutor")
    @ConditionalOnMissingBean(name = "queryPartitionTaskExecutor")
    public TaskExecutor queryPartitionTaskExecutor(final PartitioningProperties partitioningProperties) {
        final ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setThreadNamePrefix("query-partition-");

        // Slightly biased towards query grid size but still bounded by ingestion defaults.
        final int corePoolSize = Math.min(
                ingestionProperties.getCorePoolSize(),
                Math.max(1, partitioningProperties.queryGridSize())
        );
        final int maxPoolSize = Math.min(
                ingestionProperties.getMaxPoolSize(),
                Math.max(corePoolSize, partitioningProperties.queryGridSize())
        );

        threadPoolTaskExecutor.setCorePoolSize(corePoolSize);
        threadPoolTaskExecutor.setMaxPoolSize(maxPoolSize);
        threadPoolTaskExecutor.setQueueCapacity(ingestionProperties.getQueueCapacity());
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        threadPoolTaskExecutor.setAwaitTerminationSeconds(30);
        threadPoolTaskExecutor.initialize();
        return threadPoolTaskExecutor;
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "cdk.storage.azure", name = "mode", havingValue = AZURITE_MODE)
    @ConditionalOnMissingBean(name = "azuriteContainer")
    public GenericContainer<?> azuriteContainer(final StorageProperties storageProperties) {
        final String imageName = storageProperties.azurite() != null
                && StringUtils.isNotBlank(storageProperties.azurite().image())
                ? storageProperties.azurite().image()
                : DEFAULT_AZURITE_IMAGE;

        final GenericContainer<?> genericContainer =
                new GenericContainer<>(DockerImageName.parse(imageName))
                        .withExposedPorts(AZURITE_PORT)
                        .withCommand("azurite-blob --loose --blobHost 0.0.0.0 --blobPort " + AZURITE_PORT)
                        .withStartupTimeout(Duration.ofSeconds(60));

        genericContainer.start();
        return genericContainer;
    }

    @Bean
    @ConditionalOnMissingBean
    public BlobContainerClient blobContainerClient(final StorageProperties storageProperties,
                                                   @Autowired(required = false) final GenericContainer<?> azuriteContainer) {

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
            case AZURITE_MODE -> {
                if (azuriteContainer == null) {
                    throw new IllegalStateException(
                            "Azurite mode selected but azuriteContainer was not started"
                    );
                }
                final String host = azuriteContainer.getHost();
                final int mappedPort = azuriteContainer.getMappedPort(AZURITE_PORT);
                final String blobEndpoint =
                        "http://" + host + ":" + mappedPort + "/" + DEV_ACCOUNT_NAME;

                final String azuriteConnectionString =
                        "DefaultEndpointsProtocol=http;"
                                + "AccountName=" + DEV_ACCOUNT_NAME + ";"
                                + "AccountKey=" + DEV_ACCOUNT_KEY + ";"
                                + "BlobEndpoint=" + blobEndpoint + ";";

                final BlobContainerClient blobContainerClient = new BlobContainerClientBuilder()
                        .connectionString(azuriteConnectionString)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(blobContainerClient, containerName);
                yield blobContainerClient;
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported cdk.storage.azure.mode: " + mode
            );
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
