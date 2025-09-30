package uk.gov.hmcts.cp.repo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.domain.IngestionStatus;
import uk.gov.hmcts.cp.domain.QueryVersionEntity;
import uk.gov.hmcts.cp.domain.QueryVersionKey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false" ,
                "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
        }
)
@Testcontainers
@AutoConfigureTestDatabase(replace = Replace.NONE)
class QueryVersionRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        // Let Hibernate create/drop schema for the slice test:
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @org.springframework.beans.factory.annotation.Autowired
    QueryVersionRepository repo;

    @Test
    void latest_and_asOf() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Instant t1 = Instant.parse("2025-05-01T12:00:00Z");
        Instant t2 = Instant.parse("2025-06-01T12:00:00Z");

        // Save versions with queryPrompt and status (uses new constructor)
        repo.save(new QueryVersionEntity(
                new QueryVersionKey(id1, t1),
                "Q1 @ t1",
                "Prompt for Q1 @ t1",
                IngestionStatus.UPLOADED
        ));

        repo.save(new QueryVersionEntity(
                new QueryVersionKey(id1, t2),
                "Q1 @ t2",
                "Prompt for Q1 @ t2",
                IngestionStatus.INGESTED
        ));

        repo.save(new QueryVersionEntity(
                new QueryVersionKey(id2, t1),
                "Q2 @ t1",
                "Prompt for Q2 @ t1",
                IngestionStatus.UPLOADED
        ));

        // Latest (should return latest effective_at per query)
        List<QueryVersionEntity> latest = repo.findLatestAll();
        assertThat(latest).hasSize(2);

        QueryVersionEntity id1Latest = latest.stream()
                .filter(q -> q.getQueryId().equals(id1))
                .findFirst()
                .orElseThrow();

        assertThat(id1Latest.getUserQuery()).isEqualTo("Q1 @ t2");
        assertThat(id1Latest.getQueryPrompt()).isEqualTo("Prompt for Q1 @ t2");
        assertThat(id1Latest.getStatus()).isEqualTo(IngestionStatus.INGESTED);

        // As-of snapshot
        Instant asOfInstant = Instant.parse("2025-05-15T00:00:00Z");
        List<QueryVersionEntity> asOf = repo.findLatestAsOf(asOfInstant);
        assertThat(asOf).hasSize(2);

        QueryVersionEntity id1AsOf = asOf.stream()
                .filter(q -> q.getQueryId().equals(id1))
                .findFirst()
                .orElseThrow();

        assertThat(id1AsOf.getUserQuery()).isEqualTo("Q1 @ t1");
        assertThat(id1AsOf.getQueryPrompt()).isEqualTo("Prompt for Q1 @ t1");
        assertThat(id1AsOf.getStatus()).isEqualTo(IngestionStatus.UPLOADED);
    }
}
