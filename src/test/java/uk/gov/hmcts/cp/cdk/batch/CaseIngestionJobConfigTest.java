package uk.gov.hmcts.cp.cdk.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
                "management.health.defaults.enabled=false"
        }
)
class CaseIngestionJobConfigTest {

    @Autowired
    private Job caseIngestionJob;

    @Autowired
    private ExecutionContextPromotionListener eligibleMaterialListener;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withReuse(true);

    @DynamicPropertySource
    static void dbProps(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.main.web-application-type", () -> "none");
        registry.add("spring.batch.job.enabled", () -> "false");

        // DDL + Spring Batch metadata + Flyway
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");

        // New scheduler properties (we drive scheduler manually in tests)
        registry.add("cdk.ingestion.verify.scheduler.enabled", () -> "false");
        registry.add("cdk.ingestion.verify.scheduler.delay-ms", () -> "50");
        registry.add("cdk.ingestion.verify.scheduler.batch-size", () -> "10");
        registry.add("cdk.ingestion.verify.scheduler.max-attempts", () -> "10");
        registry.add("cdk.ingestion.verify.scheduler.lock-ttl-ms", () -> "10000");

        // required audit prop
        registry.add("audit.http.openapi-rest-spec", () -> "test-openapi-spec.yml");

        // Storage
        registry.add("cdk.storage.azure.connection-string",
                () -> "DefaultEndpointsProtocol=http;AccountName=dev;AccountKey=dev;BlobEndpoint=http://localhost:10000/dev;");
        registry.add("cdk.storage.azure.container", () -> "test");
        registry.add("cdk.storage.azure.mode", () -> "connection-string");

        // Add this to satisfy client.baseUrls
        registry.add("rag.client.baseUrl", () -> "http://localhost:8080");
        registry.add("cqrs.client.baseUrl", () -> "http://localhost:8080");
        registry.add("cp.audit.enabled", () -> "false");
    }

    @Test
    void caseIngestionJobCreated() {
        assertThat(caseIngestionJob).isNotNull();
    }

}