package uk.gov.hmcts.cp.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "ingestion_status_history")
public class IngestionStatusHistoryEntity {

    @Id
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IngestionStatus status;

    /** JPA needs a no-arg ctor; keep it protected. */
    protected IngestionStatusHistoryEntity() { }

    public IngestionStatusHistoryEntity(Instant changedAt, IngestionStatus status) {
        this.changedAt = changedAt;
        this.status = status;
    }

    /** Ensure PK is set if not provided explicitly. */
    @PrePersist
    void prePersist() {
        if (changedAt == null) {
            changedAt = Instant.now();
        }
    }

    public Instant getChangedAt() { return changedAt; }
    public IngestionStatus getStatus() { return status; }
}
