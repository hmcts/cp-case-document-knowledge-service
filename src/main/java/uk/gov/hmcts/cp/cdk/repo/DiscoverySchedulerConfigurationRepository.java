package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscoverySchedulerConfigurationRepository extends JpaRepository<DiscoverySchedulerConfiguration, UUID> {

    @Query(value = "SELECT * FROM discovery_scheduler_configuration "
            + "WHERE court_centre_id = :courtCentreId AND court_room_id = :courtRoomId "
            + "ORDER BY version DESC LIMIT 1", nativeQuery = true)
    Optional<DiscoverySchedulerConfiguration> findLatestByCourtCentreAndCourtRoom(
            @Param("courtCentreId") UUID courtCentreId, @Param("courtRoomId") UUID courtRoomId);

    @Query(value = "SELECT DISTINCT ON (court_centre_id, court_room_id) * "
            + "FROM discovery_scheduler_configuration "
            + "WHERE is_active = true "
            + "ORDER BY court_centre_id, court_room_id, version DESC", nativeQuery = true)
    List<DiscoverySchedulerConfiguration> findLatestActiveConfigurations();

    boolean existsByCourtCentreIdAndCourtRoomIdAndVersion(UUID courtCentreId, UUID courtRoomId, Integer version);
}
