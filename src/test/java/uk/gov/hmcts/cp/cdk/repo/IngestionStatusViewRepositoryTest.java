package uk.gov.hmcts.cp.cdk.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=true",
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
        // belt-and-braces; harmless if not needed
        r.add("spring.jpa.properties.hibernate.hbm2ddl.auto", () -> "create-drop");
        r.add("spring.sql.init.mode", () -> "never");
    }

    @BeforeEach
    void initViewAndData() {
        // Minimal base table used by the view + inserts (create only if missing)
        jdbc.execute("""
                    DO $$
                    BEGIN
                      IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename='case_documents') THEN
                        CREATE TABLE public.case_documents (
                          doc_id             UUID         PRIMARY KEY,
                          case_id            UUID         NOT NULL,
                          source             VARCHAR(64)  NOT NULL,
                          doc_name           VARCHAR(255) NOT NULL,
                          blob_uri           VARCHAR(1024),
                          uploaded_at        TIMESTAMPTZ  NOT NULL,
                          ingestion_phase    VARCHAR(64)  NOT NULL,
                          ingestion_phase_at TIMESTAMPTZ  NOT NULL
                        );
                        CREATE INDEX IF NOT EXISTS idx_case_docs_case_uploaded ON public.case_documents (case_id, uploaded_at);
                      END IF;
                    END $$;
                """);

        // Create/replace the view that the repository queries
        jdbc.execute("""
                    CREATE OR REPLACE VIEW v_case_ingestion_status AS
                    SELECT DISTINCT ON (case_id)
                      case_id,
                      ingestion_phase    AS phase,
                      ingestion_phase_at AS last_updated
                    FROM case_documents
                    ORDER BY case_id, ingestion_phase_at DESC;
                """);

        // Seed data
        caseId = UUID.randomUUID();

        jdbc.update("""
                            INSERT INTO case_documents (doc_id, case_id,material_id, source, doc_name, blob_uri, uploaded_at, ingestion_phase, ingestion_phase_at)
                            VALUES (?, ?, ?,'IDPC', 'material_id_1', 'blob://uri', ?, 'INGESTING', ?)
                        """,
                UUID.randomUUID(),
                caseId,
                UUID.randomUUID(),
                OffsetDateTime.parse("2025-05-01T12:00:00Z"),
                OffsetDateTime.parse("2025-05-01T12:00:00Z")
        );

        jdbc.update("""
                            INSERT INTO case_documents (doc_id, case_id,material_id, source, doc_name, blob_uri, uploaded_at, ingestion_phase, ingestion_phase_at)
                            VALUES (?, ?,?, 'IDPC', 'material_id_2', 'blob://uri2', ?, 'INGESTED', ?)
                        """,
                UUID.randomUUID(),
                caseId,
                UUID.randomUUID(),
                OffsetDateTime.parse("2025-05-01T12:05:00Z"),
                OffsetDateTime.parse("2025-05-01T12:05:00Z")
        );
    }

    @AfterEach
    void cleanup() {
        // Clean view & table so each test starts fresh (drops view if exists; truncates table if exists)
        jdbc.execute("DROP VIEW IF EXISTS v_case_ingestion_status");
        jdbc.execute("""
                    DO $$
                    BEGIN
                      IF to_regclass('public.case_documents') IS NOT NULL THEN
                        TRUNCATE TABLE public.case_documents;
                      END IF;
                    END $$;
                """);
    }

    @Test
    @Transactional
    @DisplayName("View returns latest phase per case")
    void view_returns_latest_phase_per_case() {
        final Optional<IngestionStatusViewRepository.Row> row = repo.findByCaseId(caseId);
        assertTrue(row.isPresent(), "Expected a row for inserted caseId");
        assertEquals("INGESTED", row.get().phase());
        assertEquals(OffsetDateTime.parse("2025-05-01T12:05:00Z"), row.get().lastUpdated());
    }
}
