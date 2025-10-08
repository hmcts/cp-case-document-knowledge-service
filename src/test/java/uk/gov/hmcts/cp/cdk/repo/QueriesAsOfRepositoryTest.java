package uk.gov.hmcts.cp.cdk.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatus;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryLifecycleStatus;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;

@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=false",
                "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
        }
)
@Testcontainers
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(QueriesAsOfRepository.class)
class QueriesAsOfRepositoryTest {


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

    @DynamicPropertySource
    static void dbProps(final DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }


    @jakarta.annotation.Resource
    private QueriesAsOfRepository repo;

    @jakarta.annotation.Resource
    private QueryVersionRepository versionRepo;

    @jakarta.annotation.Resource
    private QueryRepository queryRepo;

    @PersistenceContext
    private EntityManager em;

    private UUID caseId;
    private UUID qid;

    @BeforeEach
    void seed() {
        caseId = UUID.randomUUID();
        qid = UUID.randomUUID();

        final Query q = new Query();
        q.setQueryId(qid);
        q.setLabel("Defendant Position");
        queryRepo.saveAndFlush(q);

        final OffsetDateTime t1 = OffsetDateTime.parse("2025-05-01T11:58:00Z");
        final OffsetDateTime t2 = OffsetDateTime.parse("2025-05-01T11:59:00Z");

        final QueryVersion v1 = new QueryVersion();
        v1.setQuery(q);
        v1.setQueryVersionId(new QueryVersionId(qid, t1));
        v1.setUserQuery("def v1");
        v1.setQueryPrompt("prompt v1");
        versionRepo.save(v1);

        final QueryVersion v2 = new QueryVersion();
        v2.setQuery(q);
        v2.setQueryVersionId(new QueryVersionId(qid, t2));
        v2.setUserQuery("def v2");
        v2.setQueryPrompt("prompt v2");
        versionRepo.saveAndFlush(v2);

        final CaseQueryStatus cqs = new CaseQueryStatus();
        cqs.setCaseId(caseId);
        cqs.setQueryId(qid);
        cqs.setStatus(QueryLifecycleStatus.ANSWER_NOT_AVAILABLE);
        em.persist(cqs);
        em.flush();
    }

    @Test
    void listForCaseAsOf_returns_definition_and_status() {
        final OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T12:00:00Z");
        final List<Object[]> rows = repo.listForCaseAsOf(caseId, asOf);

        assertNotNull(rows);
        assertEquals(1, rows.size());
        final Object[] r = rows.get(0);
        assertEquals(qid, r[0]);
        assertEquals(caseId, r[1]);
        assertEquals("Defendant Position", r[2]);
        assertEquals("def v2", r[3]);
        assertEquals("prompt v2", r[4]);
        assertEquals("ANSWER_NOT_AVAILABLE", r[6]);
    }

    @Test
    void getOneForCaseAsOf_retrieves_single_row() {
        final OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T12:00:00Z");
        final Object[] r = repo.getOneForCaseAsOf(caseId, qid, asOf);
        assertNotNull(r);
        assertEquals(qid, r[0]);
        assertEquals(caseId, r[1]);
    }
}
