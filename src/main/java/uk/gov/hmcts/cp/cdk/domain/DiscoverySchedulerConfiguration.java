package uk.gov.hmcts.cp.cdk.domain;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@Entity
@Table(
        name = "discovery_scheduler_configuration",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_dsc_centre_room_version",
                        columnNames = {
                                "court_centre_id",
                                "court_room_id",
                                "version"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_dsc_centre_room",
                        columnList = "court_centre_id, court_room_id"
                )
        }
)
public class DiscoverySchedulerConfiguration {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "court_centre_id", nullable = false)
    private UUID courtCentreId;

    @Column(name = "court_room_id", nullable = false)
    private UUID courtRoomId;

    @Column(name = "uploaded_date", nullable = false)
    private LocalDate uploadedDate;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = utcNow();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = utcNow();
}
