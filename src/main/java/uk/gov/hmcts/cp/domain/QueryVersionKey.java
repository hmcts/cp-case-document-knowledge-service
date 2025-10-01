package uk.gov.hmcts.cp.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class QueryVersionKey implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID queryId;
    private Instant effectiveAt;

    public QueryVersionKey() {
        // JPA
    }

    public QueryVersionKey(final UUID queryId, final Instant effectiveAt) {
        this.queryId = queryId;
        this.effectiveAt = effectiveAt;
    }

    public UUID getQueryId() {
        return queryId;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    @Override
    public boolean equals(final Object obj) {
        boolean result = false;

        if (this == obj) {
            result = true;
        } else if (obj != null && getClass() == obj.getClass()) {
            final QueryVersionKey other = (QueryVersionKey) obj;
            result = Objects.equals(queryId, other.queryId)
                    && Objects.equals(effectiveAt, other.effectiveAt);
        }

        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryId, effectiveAt);
    }

    @Override
    public String toString() {
        return "QueryVersionKey[queryId=" + queryId + ", effectiveAt=" + effectiveAt + "]";
    }
}
