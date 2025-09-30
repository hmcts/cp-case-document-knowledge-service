package uk.gov.hmcts.cp.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class QueryVersionKey implements Serializable {
    private UUID queryId;
    private Instant effectiveAt;

    public QueryVersionKey() {}
    public QueryVersionKey(UUID queryId, Instant effectiveAt) {
        this.queryId = queryId; this.effectiveAt = effectiveAt;
    }

    public UUID getQueryId() { return queryId; }
    public Instant getEffectiveAt() { return effectiveAt; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueryVersionKey q)) return false;
        return Objects.equals(queryId, q.queryId) && Objects.equals(effectiveAt, q.effectiveAt);
    }
    @Override public int hashCode() { return Objects.hash(queryId, effectiveAt); }
}
