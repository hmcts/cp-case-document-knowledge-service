package uk.gov.hmcts.cp.cdk.batch;

import static java.util.Objects.requireNonNull;

import uk.gov.hmcts.cp.cdk.batch.storage.AzureBlobStorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageProperties;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.UploadProperties;

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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@Configuration
@EnableConfigurationProperties({
        StorageProperties.class,
        UploadProperties.class,
        IngestionProperties.class,
        PartitioningProperties.class
})
public class BatchConfig {

    private static final int AZURITE_PORT = 10_000;
    private static final String DEFAULT_AZURITE_IMAGE =
            "mcr.microsoft.com/azure-storage/azurite:3.33.0";
    private static final String DEV_ACCOUNT_NAME = "devstoreaccount1";
    private static final String DEV_ACCOUNT_KEY = "REDACTED";
    private static final String CONNECTION_STRING = "connection-string";
    private static final String MANAGED_IDENTITY = "managed-identity";
    private static final String AZURITE = "azurite";

    private final IngestionProperties ingestionProperties;

    public BatchConfig(final IngestionProperties ingestionProperties) {
        this.ingestionProperties = ingestionProperties;
    }

    @Bean
    public RetryTemplate retryTemplate() {
        final RetryTemplate retryTemplate = new RetryTemplate();

        final SimpleRetryPolicy retryPolicy =
                new SimpleRetryPolicy(ingestionProperties.getRetry().getMaxAttempts());

        final ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(
                ingestionProperties.getRetry().getBackoff().getInitialMs());
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(
                ingestionProperties.getRetry().getBackoff().getMaxMs());

        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
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
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ingestion-");
        executor.setCorePoolSize(ingestionProperties.getCorePoolSize());
        executor.setMaxPoolSize(ingestionProperties.getMaxPoolSize());
        executor.setQueueCapacity(ingestionProperties.getQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated executor for query-level partitioning in step 6.
     * Keeps nested partitions from contending with the ingestion pool.
     */
    @Bean(name = "queryPartitionTaskExecutor")
    @ConditionalOnMissingBean(name = "queryPartitionTaskExecutor")
    public TaskExecutor queryPartitionTaskExecutor(final PartitioningProperties partitionProps) {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("query-partition-");
        // tie pool size to query grid size to keep it predictable
        executor.setCorePoolSize(ingestionProperties.getCorePoolSize());
        executor.setMaxPoolSize(ingestionProperties.getMaxPoolSize());
        executor.setQueueCapacity(ingestionProperties.getQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean
    public ObjectMapper objectMapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "cdk.storage.azure", name = "mode", havingValue = AZURITE)
    @ConditionalOnMissingBean(name = "azuriteContainer")
    public GenericContainer<?> azuriteContainer(final StorageProperties storageProperties) {
        final String imageName = storageProperties.azurite() != null
                && StringUtils.isNotBlank(storageProperties.azurite().image())
                ? storageProperties.azurite().image()
                : DEFAULT_AZURITE_IMAGE;

        final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(imageName))
                .withExposedPorts(AZURITE_PORT)
                .withCommand("azurite-blob --loose --blobHost 0.0.0.0 --blobPort " + AZURITE_PORT)
                .withStartupTimeout(Duration.ofSeconds(60));
        container.start();
        return container;
    }

    @Bean
    @ConditionalOnMissingBean
    public BlobContainerClient blobContainerClient(final StorageProperties storageProperties,
                                                   @Autowired(required = false)
                                                   final GenericContainer<?> azuriteContainer) {
        final String mode = StringUtils.defaultIfBlank(
                        storageProperties.mode(), CONNECTION_STRING)
                .toLowerCase(Locale.ROOT);

        final String containerName = requireNonNull(
                storageProperties.container(), "cdk.storage.azure.container is required");

        return switch (mode) {
            case CONNECTION_STRING -> {
                final String connectionString = requireNonNull(
                        storageProperties.connectionString(),
                        "cdk.storage.azure.connection-string is required for connection-string mode"
                );
                final BlobContainerClient client = new BlobContainerClientBuilder()
                        .connectionString(connectionString)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(client, containerName);
                yield client;
            }
            case MANAGED_IDENTITY -> {
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

                final TokenCredential credential = userAssignedClientId != null
                        ? new ManagedIdentityCredentialBuilder().clientId(userAssignedClientId).build()
                        : new DefaultAzureCredentialBuilder().build();

                final BlobContainerClient client = new BlobContainerClientBuilder()
                        .endpoint(endpoint)
                        .credential(credential)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(client, containerName);
                yield client;
            }
            case AZURITE -> {
                if (azuriteContainer == null) {
                    throw new IllegalStateException(
                            "Azurite mode selected but azuriteContainer was not started");
                }
                final String host = azuriteContainer.getHost();
                final int mappedPort = azuriteContainer.getMappedPort(AZURITE_PORT);
                final String blobEndpoint =
                        "http://" + host + ":" + mappedPort + "/" + DEV_ACCOUNT_NAME;
                final String azuriteConnectionString =
                        "DefaultEndpointsProtocol=http;AccountName=" + DEV_ACCOUNT_NAME
                                + ";AccountKey=" + DEV_ACCOUNT_KEY
                                + ";BlobEndpoint=" + blobEndpoint + ";";
                final BlobContainerClient client = new BlobContainerClientBuilder()
                        .connectionString(azuriteConnectionString)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(client, containerName);
                yield client;
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported cdk.storage.azure.mode: " + mode);
        };
    }

    private void createContainerIfMissing(final BlobContainerClient client,
                                          final String containerName) {
        try {
            client.createIfNotExists();
        } catch (RuntimeException exception) {
            log.warn("Container creation skipped or failed. container={} reason={}",
                    containerName, exception.getMessage());
        }
    }

    @Bean
    public StorageService storageService(final BlobContainerClient blobContainerClient,
                                         final StorageProperties storageProperties) {
        return new AzureBlobStorageService(blobContainerClient, storageProperties);
    }
}
