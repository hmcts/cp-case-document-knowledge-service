package uk.gov.hmcts.cp.cdk.repo;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseQueryStatusRepositoryTest {

    @jakarta.annotation.Resource
    private CaseQueryStatusRepository repository;

    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    @DisplayName("countAwaitingAnswerOlderThan counts only ANSWER_NOT_AVAILABLE rows older than the cutoff")
    void countAwaitingAnswerOlderThan_countsOnlyStaleAwaitingAnswer() {
        final OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);
        final OffsetDateTime old = cutoff.minusMinutes(5);
        final OffsetDateTime fresh = cutoff.plusMinutes(5);

        // ANSWER_NOT_AVAILABLE, older than cutoff -> counted
        persist("ANSWER_NOT_AVAILABLE", old);
        persist("ANSWER_NOT_AVAILABLE", old);

        // ANSWER_NOT_AVAILABLE, newer than cutoff -> excluded
        persist("ANSWER_NOT_AVAILABLE", fresh);

        // ANSWER_AVAILABLE, any age -> excluded regardless of status_at
        persist("ANSWER_AVAILABLE", old);

        final long count = repository.countAwaitingAnswerOlderThan(cutoff);

        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("countAwaitingAnswerOlderThan returns 0 when nothing is stale")
    void countAwaitingAnswerOlderThan_returnsZero_whenNothingStale() {
        final OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);
        persist("ANSWER_NOT_AVAILABLE", OffsetDateTime.now());

        final long count = repository.countAwaitingAnswerOlderThan(cutoff);

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("countAwaitingAnswerOlderThan carries a 5-second statement timeout (AC-006) and no @Transactional "
            + "(Scenario 3.8 — the property Story 4's per-aggregate degradation depends on)")
    void countAwaitingAnswerOlderThan_carriesStatementTimeoutAndNoSharedTransaction() throws NoSuchMethodException {
        final Method method = CaseQueryStatusRepository.class.getMethod(
                "countAwaitingAnswerOlderThan", OffsetDateTime.class);

        final QueryHints hints = method.getAnnotation(QueryHints.class);
        assertThat(hints).isNotNull();
        assertThat(hints.value()).hasSize(1);
        assertThat(hints.value()[0].name()).isEqualTo("jakarta.persistence.query.timeout");
        assertThat(hints.value()[0].value()).isEqualTo("5000");

        assertThat(method.getAnnotation(Transactional.class))
                .as("must not share a transaction with the other aggregate — a shared transaction "
                        + "would be marked rollback-only by the first failure and take the other "
                        + "aggregate down with it")
                .isNull();
    }

    private void persist(final String status, final OffsetDateTime statusAt) {
        final UUID queryId = randomUUID();
        jdbc.update("INSERT INTO queries (query_id, label) VALUES (?, ?)",
                queryId, "test-query-" + queryId);
        jdbc.update("""
                    INSERT INTO case_query_status (case_id, query_id, status, status_at)
                    VALUES (?, ?, ?::query_lifecycle_status_enum, ?)
                """, randomUUID(), queryId, status, statusAt);
    }
}
