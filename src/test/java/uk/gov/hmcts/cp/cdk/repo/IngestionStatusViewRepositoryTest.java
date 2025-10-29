// src/test/java/uk/gov/hmcts/cp/cdk/repo/IngestionStatusViewRepositoryTest.java
package uk.gov.hmcts.cp.cdk.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.DisplayName;@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false",
                "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
        }
)
@Testcontainers
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Ingestion Status View Repository tests")
@Import(IngestionStatusViewRepository.class)
class IngestionStatusViewRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
    }

    @jakarta.annotation.Resource
    private IngestionStatusViewRepository repo;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager em;
    private UUID caseId;

    @DynamicPropertySource
    static void dbProps(final DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @BeforeEach
    void initViewAndData() {
        jdbc.execute("""
                    CREATE OR REPLACE VIEW v_case_ingestion_status AS
                    SELECT DISTINCT ON (case_id)
                      case_id,
                      ingestion_phase     AS phase,
                      ingestion_phase_at  AS last_updated
                    FROM case_documents
                    ORDER BY case_id, ingestion_phase_at DESC;
                """);

        caseId = UUID.randomUUID();

        jdbc.update("""
                            INSERT INTO case_documents (doc_id, case_id, source,doc_name, blob_uri, uploaded_at, ingestion_phase, ingestion_phase_at)
                            VALUES (?, ?, 'IDPC','material_id_1', 'blob://uri', ?, 'INGESTING', ?)
                        """,
                UUID.randomUUID(),
                caseId,
                OffsetDateTime.parse("2025-05-01T12:00:00Z"),
                OffsetDateTime.parse("2025-05-01T12:00:00Z")
        );

        jdbc.update("""
                            INSERT INTO case_documents (doc_id, case_id, source,doc_name, blob_uri, uploaded_at, ingestion_phase, ingestion_phase_at)
                            VALUES (?, ?, 'IDPC','material_id_2', 'blob://uri2', ?, 'INGESTED', ?)
                        """,
                UUID.randomUUID(),
                caseId,
                OffsetDateTime.parse("2025-05-01T12:05:00Z"),
                OffsetDateTime.parse("2025-05-01T12:05:00Z")
        );
    }

    @Test
    @Transactional@DisplayName("View returns latest phase per case")
    void view_returns_latest_phase_per_case() {
        final Optional<IngestionStatusViewRepository.Row> row = repo.findByCaseId(caseId);
        assertTrue(row.isPresent());
        assertEquals("INGESTED", row.get().phase());
        assertEquals(OffsetDateTime.parse("2025-05-01T12:05:00Z"), row.get().lastUpdated());
    }
}
