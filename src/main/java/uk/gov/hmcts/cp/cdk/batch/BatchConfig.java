package uk.gov.hmcts.cp.cdk.batch;

import static java.util.Objects.requireNonNull;

import uk.gov.hmcts.cp.cdk.batch.clients.common.AzureIdentityConfig;
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
    private static final String DEFAULT_AZURITE_IMAGE = "mcr.microsoft.com/azure-storage/azurite:3.33.0";
    private static final String DEV_ACCOUNT_NAME = "devstoreaccount1";
    private static final String DEV_ACCOUNT_KEY = "Eby8vdM02xNOcqFeqCnf2w==";
    private static final String CONNECTION_STRING = "connection-string";
    private static final String MANAGED_IDENTITY = "managed-identity";
    private static final String AZURITE = "azurite";

    private final IngestionProperties ingestionProperties;

    public BatchConfig(final IngestionProperties ingestionProperties) {
        this.ingestionProperties = ingestionProperties;
    }

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(ingestionProperties.getRetry().getMaxAttempts());
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(ingestionProperties.getRetry().getBackoff().getInitialMs());
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(ingestionProperties.getRetry().getBackoff().getMaxMs());
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }

    @Bean(name = "ingestionTaskExecutor")
    @ConditionalOnMissingBean(name = "ingestionTaskExecutor")
    public TaskExecutor ingestionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ingestion-");
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
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "cdk.storage.azure", name = "mode", havingValue = AZURITE)
    @ConditionalOnMissingBean(name = "azuriteContainer")
    public GenericContainer<?> azuriteContainer(StorageProperties storageProperties) {
        String imageName = storageProperties.azurite() != null && StringUtils.isNotBlank(storageProperties.azurite().image())
                ? storageProperties.azurite().image()
                : DEFAULT_AZURITE_IMAGE;
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(imageName))
                .withExposedPorts(AZURITE_PORT)
                .withCommand("azurite-blob --loose --blobHost 0.0.0.0 --blobPort " + AZURITE_PORT)
                .withStartupTimeout(Duration.ofSeconds(60));
        container.start();
        return container;
    }

    @Bean
    @ConditionalOnMissingBean
    public BlobContainerClient blobContainerClient(StorageProperties storageProperties,
                                                   @Autowired(required = false) GenericContainer<?> azuriteContainer) {
        String mode = StringUtils.defaultIfBlank(storageProperties.mode(), CONNECTION_STRING).toLowerCase(Locale.ROOT);
        String containerName = requireNonNull(storageProperties.container(), "cdk.storage.azure.container is required");
        return switch (mode) {
            case CONNECTION_STRING -> {
                String connectionString = requireNonNull(storageProperties.connectionString(), "cdk.storage.azure.connection-string is required for connection-string mode");
                BlobContainerClient client = new BlobContainerClientBuilder()
                        .connectionString(connectionString)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(client, containerName);
                yield client;
            }
            case MANAGED_IDENTITY -> {
                String endpoint = StringUtils.trimToNull(storageProperties.blobEndpoint());
                if (endpoint == null) {
                    String accountName = requireNonNull(storageProperties.accountName(), "cdk.storage.azure.account-name is required when blob-endpoint is not provided");
                    endpoint = "https://" + accountName + ".blob.core.windows.net";
                }
                String userAssignedClientId = StringUtils.trimToNull(storageProperties.managedIdentityClientId());
                TokenCredential credential = userAssignedClientId != null
                        ? new ManagedIdentityCredentialBuilder().clientId(userAssignedClientId).build()
                        : new DefaultAzureCredentialBuilder().build();
                BlobContainerClient client = new BlobContainerClientBuilder()
                        .endpoint(endpoint)
                        .credential(credential)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(client, containerName);
                yield client;
            }
            case AZURITE -> {
                if (azuriteContainer == null) {
                    throw new IllegalStateException("Azurite mode selected but azuriteContainer was not started");
                }
                String host = azuriteContainer.getHost();
                int mappedPort = azuriteContainer.getMappedPort(AZURITE_PORT);
                String blobEndpoint = "http://" + host + ":" + mappedPort + "/" + DEV_ACCOUNT_NAME;
                String azuriteConnectionString = "DefaultEndpointsProtocol=http;AccountName=" + DEV_ACCOUNT_NAME + ";AccountKey=" + DEV_ACCOUNT_KEY + ";BlobEndpoint=" + blobEndpoint + ";";
                BlobContainerClient client = new BlobContainerClientBuilder()
                        .connectionString(azuriteConnectionString)
                        .containerName(containerName)
                        .buildClient();
                createContainerIfMissing(client, containerName);
                yield client;
            }
            default ->
                    throw new IllegalArgumentException("Unsupported cdk.storage.azure.mode: " + mode);
        };
    }

    private void createContainerIfMissing(BlobContainerClient client, String containerName) {
        try {
            client.createIfNotExists();
        } catch (RuntimeException exception) {
            log.warn("Container creation skipped or failed. container={} reason={}", containerName, exception.getMessage());
        }
    }

    @Bean
    public StorageService storageService(BlobContainerClient blobContainerClient, StorageProperties storageProperties) {
        return new AzureBlobStorageService(blobContainerClient, storageProperties);
    }
}
