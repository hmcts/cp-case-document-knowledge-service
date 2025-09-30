package uk.gov.hmcts.cp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "queries")
public class QueryEntity {

    @Id
    @Column(name = "query_id", nullable = false)
    private UUID queryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QueryEntity() {}

    public QueryEntity(UUID queryId, Instant createdAt) {
        this.queryId = Objects.requireNonNull(queryId);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public UUID getQueryId() {
        return queryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
