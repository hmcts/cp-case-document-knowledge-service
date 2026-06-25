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
        name = "scheduled_ingestion_request",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_sir_business_key",
                        columnNames = {
                                "court_centre_id",
                                "court_room_id",
                                "hearing_date"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_sir_hearing_date",
                        columnList = "hearing_date"
                )
        }
)
public class ScheduledIngestionRequest {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "cppuid", nullable = false)
    private UUID cppuid;

    @Column(name = "court_centre_id", nullable = false)
    private UUID courtCentreId;

    @Column(name = "court_room_id", nullable = false)
    private UUID courtRoomId;

    @Column(name = "hearing_date", nullable = false)
    private LocalDate hearingDate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = utcNow();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = utcNow();
}