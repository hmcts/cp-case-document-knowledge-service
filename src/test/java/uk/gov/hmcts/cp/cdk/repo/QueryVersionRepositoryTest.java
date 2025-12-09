package uk.gov.hmcts.cp.cdk.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
@AutoConfigureTestDatabase(replace = Replace.NONE)
@DisplayName("Query Version Repository tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryVersionRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

    // Ensure container is started BEFORE DynamicPropertySource is evaluated.
    static {
        POSTGRES.start();
    }

    @jakarta.annotation.Resource
    private QueryVersionRepository repo;
    @jakarta.annotation.Resource
    private QueryRepository queryRepo;
    @PersistenceContext
    private EntityManager em;
    private UUID qid;

    @DynamicPropertySource
    static void dbProps(final DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @BeforeEach
    void seed() {
        qid = UUID.randomUUID();
        final OffsetDateTime t1 = OffsetDateTime.parse("2025-05-01T11:58:00Z");
        final OffsetDateTime t2 = OffsetDateTime.parse("2025-05-01T11:59:00Z");

        final Query q = new Query(qid, "Case Summary (All Witnesses)", OffsetDateTime.now(), 200);
        queryRepo.saveAndFlush(q);

        final QueryVersion v1 = new QueryVersion(new QueryVersionId(qid, t1), q, "UQ v1", "QP v1");
        repo.save(v1);

        final QueryVersion v2 = new QueryVersion(new QueryVersionId(qid, t2), q, "UQ v2", "QP v2");
        repo.saveAndFlush(v2);
    }

    @Test
    @DisplayName("Snapshot Definitions As Of returns latest not after cutoff")
    void snapshotDefinitionsAsOf_returns_latest_not_after_cutoff() {
        final OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T11:58:30Z");

        final List<QueryVersionRepository.SnapshotDefinition> rows = repo.snapshotDefinitionsAsOf(asOf);

        assertNotNull(rows);
        assertEquals(1, rows.size());
        final QueryVersionRepository.SnapshotDefinition r = rows.get(0);
        assertEquals(qid, r.queryId());
        assertEquals("Case Summary (All Witnesses)", r.label());
        assertEquals("UQ v1", r.userQuery());
        assertEquals("QP v1", r.queryPrompt());
        assertEquals(OffsetDateTime.parse("2025-05-01T11:58:00Z").toInstant(), r.effectiveAt());
    }

    @Test
    @DisplayName("Find All Versions returns ascending effective at")
    void findAllVersions_returns_ascending_effective_at() {
        final List<QueryVersion> versions = repo.findAllVersions(qid);

        assertEquals(2, versions.size());
        assertEquals("UQ v1", versions.get(0).getUserQuery());
        assertEquals("UQ v2", versions.get(1).getUserQuery());
    }
}
