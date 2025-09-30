package uk.gov.hmcts.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

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

    /**
     * JPA needs a no-arg ctor; keep it protected.
     */
    protected IngestionStatusHistoryEntity() {
    }

    public IngestionStatusHistoryEntity(final Instant changedAt, final IngestionStatus status) {
        this.changedAt = changedAt;
        this.status = status;
    }

    /**
     * Ensure PK is set if not provided explicitly.
     */
    @PrePersist
    void prePersist() {
        if (changedAt == null) {
            changedAt = Instant.now();
        }
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public IngestionStatus getStatus() {
        return status;
    }
}
