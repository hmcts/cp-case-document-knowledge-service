package uk.gov.hmcts.cp.cdk.repo;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@DisplayName("Discovery Scheduler Configuration Repository tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscoverySchedulerConfigurationRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Resource
    private DiscoverySchedulerConfigurationRepository repository;

    private UUID courtCentreId;
    private UUID courtRoomId;

    @BeforeEach
    void seed() {
        courtCentreId = UUID.randomUUID();
        courtRoomId = UUID.randomUUID();

        repository.saveAndFlush(configuration(courtCentreId, courtRoomId, 1, true));
        repository.saveAndFlush(configuration(courtCentreId, courtRoomId, 2, false));
    }

    @Test
    @DisplayName("findLatestByCourtCentreAndCourtRoom returns highest version for a pair")
    void findLatestByCourtCentreAndCourtRoom_returns_highest_version() {
        final Optional<DiscoverySchedulerConfiguration> latest =
                repository.findLatestByCourtCentreAndCourtRoom(courtCentreId, courtRoomId);

        assertThat(latest).isPresent();
        assertThat(latest.get().getVersion()).isEqualTo(2);
        assertThat(latest.get().isActive()).isFalse();
    }

    @Test
    @DisplayName("findLatestByCourtCentreAndCourtRoom ignores lower versions of other pairs")
    void findLatestByCourtCentreAndCourtRoom_ignores_other_pairs() {
        final UUID otherCourtRoomId = UUID.randomUUID();
        repository.saveAndFlush(configuration(courtCentreId, otherCourtRoomId, 1, true));

        final Optional<DiscoverySchedulerConfiguration> latest =
                repository.findLatestByCourtCentreAndCourtRoom(courtCentreId, courtRoomId);

        assertThat(latest).isPresent();
        assertThat(latest.get().getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("findLatestByCourtCentreAndCourtRoom returns empty when none exist")
    void findLatestByCourtCentreAndCourtRoom_returns_empty_when_none() {
        final Optional<DiscoverySchedulerConfiguration> latest =
                repository.findLatestByCourtCentreAndCourtRoom(UUID.randomUUID(), UUID.randomUUID());

        assertThat(latest).isEmpty();
    }

    @Test
    @DisplayName("findLatestActiveConfigurations skips an inactive latest version and returns the latest active one for the pair")
    void findLatestActiveConfigurations_skips_inactive_latest() {
        // seeded: v1 active=true, v2 active=false (v2 is the overall latest, but inactive)
        final List<DiscoverySchedulerConfiguration> latestActive = repository.findLatestActiveConfigurations();

        final Optional<DiscoverySchedulerConfiguration> forSeededPair = latestActive.stream()
                .filter(c -> c.getCourtCentreId().equals(courtCentreId) && c.getCourtRoomId().equals(courtRoomId))
                .findFirst();

        assertThat(forSeededPair).isPresent();
        assertThat(forSeededPair.get().getVersion()).isEqualTo(1);
        assertThat(forSeededPair.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("findLatestActiveConfigurations returns the highest active version for a pair, ignoring an inactive version in between")
    void findLatestActiveConfigurations_returns_highest_active_ignoring_inactive_gap() {
        repository.saveAndFlush(configuration(courtCentreId, courtRoomId, 3, true));

        final List<DiscoverySchedulerConfiguration> latestActive = repository.findLatestActiveConfigurations();

        final Optional<DiscoverySchedulerConfiguration> forSeededPair = latestActive.stream()
                .filter(c -> c.getCourtCentreId().equals(courtCentreId) && c.getCourtRoomId().equals(courtRoomId))
                .findFirst();

        assertThat(forSeededPair).isPresent();
        assertThat(forSeededPair.get().getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("findLatestActiveConfigurations excludes pairs with no active version at all")
    void findLatestActiveConfigurations_excludes_pairs_with_no_active_version() {
        final UUID centreWithOnlyInactiveVersions = UUID.randomUUID();
        final UUID roomWithOnlyInactiveVersions = UUID.randomUUID();
        repository.saveAndFlush(configuration(centreWithOnlyInactiveVersions, roomWithOnlyInactiveVersions, 1, false));

        final List<DiscoverySchedulerConfiguration> latestActive = repository.findLatestActiveConfigurations();

        assertThat(latestActive)
                .noneMatch(c -> c.getCourtCentreId().equals(centreWithOnlyInactiveVersions)
                        && c.getCourtRoomId().equals(roomWithOnlyInactiveVersions));
    }

    @Test
    @DisplayName("findLatestActiveConfigurations returns exactly one row per court centre/court room pair")
    void findLatestActiveConfigurations_returns_one_row_per_pair() {
        final List<DiscoverySchedulerConfiguration> latestActive = repository.findLatestActiveConfigurations();

        final long matchesForSeededPair = latestActive.stream()
                .filter(c -> c.getCourtCentreId().equals(courtCentreId) && c.getCourtRoomId().equals(courtRoomId))
                .count();

        assertThat(matchesForSeededPair).isEqualTo(1);
    }

    @Test
    @DisplayName("existsByCourtCentreIdAndCourtRoomIdAndVersion detects duplicate version")
    void existsByCourtCentreIdAndCourtRoomIdAndVersion_detects_duplicate() {
        assertThat(repository.existsByCourtCentreIdAndCourtRoomIdAndVersion(courtCentreId, courtRoomId, 1)).isTrue();
        assertThat(repository.existsByCourtCentreIdAndCourtRoomIdAndVersion(courtCentreId, courtRoomId, 3)).isFalse();
    }

    private static DiscoverySchedulerConfiguration configuration(final UUID courtCentreId, final UUID courtRoomId,
                                                                   final int version, final boolean active) {
        final DiscoverySchedulerConfiguration config = new DiscoverySchedulerConfiguration();
        config.setId(UUID.randomUUID());
        config.setCourtCentreId(courtCentreId);
        config.setCourtRoomId(courtRoomId);
        config.setUploadedDate(LocalDate.of(2026, 6, 16));
        config.setVersion(version);
        config.setActive(active);
        return config;
    }
}
