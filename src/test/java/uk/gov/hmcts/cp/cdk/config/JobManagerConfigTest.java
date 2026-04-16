package uk.gov.hmcts.cp.cdk.config;


import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.storage.StorageService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=test",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.jms.listener.auto-startup=false",
                "management.health.jms.enabled=false",
                "management.health.defaults.enabled=false",
                "cp.audit.hosts=localhost",
                "cp.audit.port=61616",
                "cdk.storage.azure.connection-string=DefaultEndpointsProtocol=http;AccountName=dev;AccountKey=dev;BlobEndpoint=http://localhost:10000/dev;"
        }
)
class JobManagerConfigTest {

    @Autowired
    private StorageService storageService;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withReuse(true);

    @Test
    void caseStorageServiceCreated() {
        assertThat(storageService).isNotNull();
    }
}