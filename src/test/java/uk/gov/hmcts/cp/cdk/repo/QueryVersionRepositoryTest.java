package uk.gov.hmcts.cp.cdk.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import jakarta.annotation.Resource;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;

@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
        }
)
@Testcontainers
@AutoConfigureTestDatabase
// @TestInstance
class QueryVersionRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withExposedPorts(5432)
                    .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
                            new HostConfig().withPortBindings(new PortBinding(Ports.Binding.bindPort(5432), new ExposedPort(5432)))
                    ))
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
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @jakarta.annotation.Resource
    private QueryVersionRepository repo;

    @Resource
    private QueryRepository queryRepo;


    private UUID qid;

    @BeforeEach
    void seed() {
//        qid = UUID.randomUUID();
//        final Query q = new Query();
//        q.setQueryId(qid);
//        q.setLabel("Case Summary (All Witnesses)");
//        queryRepo.saveAndFlush(q);
//
//        final OffsetDateTime t1 = OffsetDateTime.parse("2025-05-01T11:58:00Z");
//        final OffsetDateTime t2 = OffsetDateTime.parse("2025-05-01T11:59:00Z");
//
//        final QueryVersion v1 = new QueryVersion();
//        v1.setQuery(q);
//        v1.setQueryVersionId(new QueryVersionId(qid, t1));
//        v1.setUserQuery("UQ v1");
//        v1.setQueryPrompt("QP v1");
//        repo.save(v1);
//
//        final QueryVersion v2 = new QueryVersion();
//        v2.setQuery(q);
//        v2.setQueryVersionId(new QueryVersionId(qid, t2));
//        v2.setUserQuery("UQ v2");
//        v2.setQueryPrompt("QP v2");
//        repo.saveAndFlush(v2);
    }

    @Test
    void snapshotDefinitionsAsOf_returns_latest_not_after_cutoff() {
        final OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T11:58:30Z");
        final List<Object[]> rows = repo.snapshotDefinitionsAsOf(asOf);

        assertNotNull(rows);
        assertEquals(1, rows.size());
        final Object[] r = rows.get(0);
        assertEquals(qid, r[0]);
        assertEquals("Case Summary (All Witnesses)", r[1]); // label
        assertEquals("UQ v1", r[2]);
        assertEquals("QP v1", r[3]);
    }

    @Test
    void findAllVersions_returns_ascending_effective_at() {
        final List<QueryVersion> versions = repo.findAllVersions(qid);
        assertEquals(2, versions.size());
        assertEquals("UQ v1", versions.get(0).getUserQuery());
        assertEquals("UQ v2", versions.get(1).getUserQuery());
    }
}
