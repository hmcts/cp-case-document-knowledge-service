package uk.gov.hmcts.cp.cdk.repo;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.INGESTED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.UPLOADED;

import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseDocumentRepositoryTest {

    @jakarta.annotation.Resource
    private CaseDocumentRepository repository;

    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
    }

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

    @Test
    @DisplayName("Should return distinct doc_ids for matching caseId, defendantId and INGESTED phase")
    void findSupersededDocuments_success() {
        final UUID caseId = randomUUID();
        final UUID defendantId = randomUUID();
        final UUID docId1 = randomUUID();
        final UUID docId2 = randomUUID();

        // matching records
        persist(docId1, caseId, defendantId, INGESTED);
        persist(docId2, caseId, defendantId, INGESTED);

        // non-matching records
        persist(randomUUID(), caseId, defendantId, UPLOADED);
        persist(randomUUID(), defendantId, randomUUID(), INGESTED);

        final List<UUID> result = repository.findSupersededDocuments(caseId, defendantId);

        assertThat(result).hasSize(2).containsExactlyInAnyOrder(docId1, docId2);
    }

    @Test
    @DisplayName("Should return empty list when no records match")
    void findSupersededDocuments_noMatch() {
        final UUID caseId = randomUUID();
        final UUID defendantId = randomUUID();

        persist(randomUUID(), randomUUID(), randomUUID(), INGESTED);

        final List<UUID> result = repository.findSupersededDocuments(caseId, defendantId);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnSupersededDocumentsByCaseId() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId1 = UUID.randomUUID();
        final UUID docId2 = UUID.randomUUID();

        persist(docId1, caseId, null, INGESTED);
        persist(docId2, caseId, null, INGESTED);

        final List<UUID> result = repository.findSupersededDocuments(caseId);

        assertThat(result).hasSize(2).containsExactlyInAnyOrder(docId1, docId2);
    }

    @Test
    void shouldReturnSupersededDocumentsByCaseIdAndExcludeRowsWithDefendantId() {
        final UUID caseId = UUID.randomUUID();
        persist(randomUUID(), caseId, randomUUID(), INGESTED);

        final List<UUID> result = repository.findSupersededDocuments(caseId);

        assertThat(result).isEmpty();
    }


    private void persist(final UUID docId, final UUID caseId, final UUID defendantId, final DocumentIngestionPhase phase) {
        jdbc.update("""
                    INSERT INTO case_documents
                    (doc_id, case_id, material_id, source, doc_name, 
                     blob_uri, content_type, size_bytes, sha256_hex, 
                     uploaded_at, ingestion_phase, ingestion_phase_at, defendant_id, courtdoc_id, created_at)
                    VALUES(?, ?, ?, 'IDPC', '', '', '', 0, '', now(), ?::document_ingestion_phase_enum, now(), ?, ?, now())
                """, docId, caseId, randomUUID(), phase.name(), defendantId, randomUUID());
    }
}