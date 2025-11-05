package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageProperties;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "storage.connection-string", havingValue = "auto-azurite")
public class AzuriteAutoConfig {

    @Bean(destroyMethod = "stop")
    public GenericContainer<?> azuriteContainer(
            @Value("${storage.azurite.image:mcr.microsoft.com/azure-storage/azurite:3.33.0}") String image) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(10000)
                .withCommand("azurite-blob --loose --blobHost 0.0.0.0 --blobPort 10000")
                .withStartupTimeout(Duration.ofSeconds(60));
        c.start();
        return c;
    }

    @Bean
    @Primary
    public StorageProperties azuriteStorageProperties(
            GenericContainer<?> azuriteContainer,
            @Value("${storage.container:cdk}") String container,
            @Value("${storage.copyPollIntervalMs:1000}") Long copyPollIntervalMs,
            @Value("${storage.copyTimeoutSeconds:120}") Long copyTimeoutSeconds) {

        int mapped = azuriteContainer.getMappedPort(10000);
        String host = azuriteContainer.getHost();
        String blobEndpoint = "http://" + host + ":" + mapped + "/devstoreaccount1";
        String connectionString =
                "DefaultEndpointsProtocol=http;" +
                        "AccountName=devstoreaccount1;" +
                        "AccountKey=Eby8vdM02xNOcqFeqCnf2w==;" +
                        "BlobEndpoint=" + blobEndpoint + ";";

        return new StorageProperties(connectionString, container, copyPollIntervalMs, copyTimeoutSeconds);
    }
}
