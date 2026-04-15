package uk.gov.hmcts.cp.cdk.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatus;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryLevel;
import uk.gov.hmcts.cp.cdk.domain.QueryLifecycleStatus;
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
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Queries As Of Repository tests")
class QueriesAsOfRepositoryTest {


    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

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
        final OffsetDateTime t1 = OffsetDateTime.parse("2025-05-01T11:58:00Z");
        final OffsetDateTime t2 = OffsetDateTime.parse("2025-05-01T11:59:00Z");

        final Query q = new Query(qid, "Defendant Position", OffsetDateTime.now(), 200,true);
        queryRepo.saveAndFlush(q);

        final QueryVersion v1 = new QueryVersion(new QueryVersionId(qid, t1), q, "def v1", "prompt v1", QueryLevel.CASE);
        versionRepo.save(v1);

        final QueryVersion v2 = new QueryVersion(new QueryVersionId(qid, t2), q, "def v2", "prompt v2",QueryLevel.CASE);
        versionRepo.saveAndFlush(v2);

        final CaseQueryStatus cqs = new CaseQueryStatus();
        cqs.setCaseId(caseId);
        cqs.setQueryId(qid);
        cqs.setStatus(QueryLifecycleStatus.ANSWER_NOT_AVAILABLE);
        em.persist(cqs);
        em.flush();
    }

    @Test
    @DisplayName("List For Case As Of returns definition and status")
    void listForCaseAsOf_returns_definition_and_status() {
        final OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T12:00:00Z");
        final List<QueriesAsOfRepository.QueryAsOfView> queryAsOfViews = repo.listForCaseAsOf(caseId, asOf);

        assertNotNull(queryAsOfViews);
        assertThat(queryAsOfViews.isEmpty()).isFalse();
        final QueriesAsOfRepository.QueryAsOfView r = queryAsOfViews.get(0);
        assertEquals(qid, r.queryId());
        assertEquals(caseId, r.caseId());
        assertEquals("Defendant Position", r.label());
        assertEquals("def v2", r.userQuery());
        assertEquals("prompt v2", r.queryPrompt());
        assertEquals("ANSWER_NOT_AVAILABLE", r.status());
        assertEquals(true, r.isActive());
        assertThat(r.statusAt()).isNotNull();
        assertEquals(200, r.displayOrder());
        assertEquals(OffsetDateTime.parse("2025-05-01T11:59:00Z").toInstant(), r.effectiveAt());
    }

    @Test
    @DisplayName("Get One For Case As Of retrieves single row")
    void getOneForCaseAsOf_retrieves_single_row() {
        final OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T12:00:00Z");
        final QueriesAsOfRepository.QueryAsOfView oneForCaseAsOf = repo.getOneForCaseAsOf(caseId, qid, asOf);
        assertNotNull(oneForCaseAsOf);
        assertEquals(qid, oneForCaseAsOf.queryId());
        assertEquals(caseId, oneForCaseAsOf.caseId());
    }

    @Test
    @DisplayName("List For Case As Of does not return inactive queries")
    void listForCaseAsOf_excludes_inactive_query() {
        final UUID inactiveQid = UUID.randomUUID();
        final OffsetDateTime t1 = OffsetDateTime.parse("2025-05-01T11:58:00Z");

        final Query inactiveQuery = new Query(inactiveQid, "Inactive Query", OffsetDateTime.now(), 300, false);
        queryRepo.saveAndFlush(inactiveQuery);

        final QueryVersion v1 = new QueryVersion(
                new QueryVersionId(inactiveQid, t1),
                inactiveQuery,
                "inactive def",
                "inactive prompt",
                QueryLevel.CASE
        );
        versionRepo.saveAndFlush(v1);

        final CaseQueryStatus cqs = new CaseQueryStatus();
        cqs.setCaseId(caseId);
        cqs.setQueryId(inactiveQid);
        cqs.setStatus(QueryLifecycleStatus.ANSWER_NOT_AVAILABLE);
        em.persist(cqs);
        em.flush();

        final OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        final List<QueriesAsOfRepository.QueryAsOfView> results =
                repo.listForCaseAsOf(caseId, asOf);

        assertThat(results).isNotEmpty();

        // Assert inactive query is NOT returned
        final boolean exists = results.stream()
                .anyMatch(r -> r.queryId().equals(inactiveQid));

        assertThat(exists).isFalse();
    }
}
