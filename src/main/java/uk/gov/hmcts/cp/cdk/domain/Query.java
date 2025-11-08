package uk.gov.hmcts.cp.cdk.domain;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "queries")
public class Query {

    @Id
    @Column(name = "query_id", nullable = false)
    private UUID queryId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = utcNow();

    public UUID getQueryId() {
        return queryId;
    }

    public void setQueryId(final UUID queryId) {
        this.queryId = queryId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = label;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
