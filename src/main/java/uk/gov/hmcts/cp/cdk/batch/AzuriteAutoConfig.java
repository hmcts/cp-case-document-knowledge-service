package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageProperties;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "storage.connection-string", havingValue = "auto-azurite")
public class AzuriteAutoConfig {

    private static final String DEFAULT_IMAGE = "mcr.microsoft.com/azure-storage/azurite:3.33.0";
    private static final int AZURITE_PORT = 10_000;
    private static final String DEV_ACCOUNT_NAME = "devstoreaccount1";
    private static final String DEV_ACCOUNT_KEY = "Eby8vdM02xNOcqFeqCnf2w==";

    @Bean(destroyMethod = "stop")
    public GenericContainer<?> azuriteContainer(
            @Value("${storage.azurite.image:" + DEFAULT_IMAGE + "}") final String image) {
        final GenericContainer<?> azuriteContainer = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(AZURITE_PORT)
                .withCommand("azurite-blob --loose --blobHost 0.0.0.0 --blobPort " + AZURITE_PORT)
                .withStartupTimeout(Duration.ofSeconds(60));
        azuriteContainer.start();
        return azuriteContainer;
    }

    @Bean
    @Primary
    public StorageProperties azuriteStorageProperties(
            final GenericContainer<?> azuriteContainer,
            @Value("${storage.container:cdk}") final String container,
            @Value("${storage.copyPollIntervalMs:1000}") final Long copyPollIntervalMs,
            @Value("${storage.copyTimeoutSeconds:120}") final Long copyTimeoutSeconds) {

        final int mappedPort = azuriteContainer.getMappedPort(AZURITE_PORT);
        final String host = azuriteContainer.getHost();
        final String blobEndpoint = "http://" + host + ":" + mappedPort + "/" + DEV_ACCOUNT_NAME;

        final String connectionString =
                "DefaultEndpointsProtocol=http;" +
                        "AccountName=" + DEV_ACCOUNT_NAME + ";" +
                        "AccountKey=" + DEV_ACCOUNT_KEY + ";" +
                        "BlobEndpoint=" + blobEndpoint + ";";

        return new StorageProperties(connectionString, container, copyPollIntervalMs, copyTimeoutSeconds);
    }
}
