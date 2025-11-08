package uk.gov.hmcts.cp.cdk.domain;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class QueryVersionId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // Mapped via @MapsId/@JoinColumn on QueryVersion
    private UUID queryId;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private OffsetDateTime effectiveAt;

    public QueryVersionId() {
        // JPA
    }

    public QueryVersionId(final UUID queryId, final OffsetDateTime effectiveAt) {
        this.queryId = queryId;
        this.effectiveAt = effectiveAt;
    }

    public UUID getQueryId() {
        return queryId;
    }

    public void setQueryId(final UUID queryId) {
        this.queryId = queryId;
    }

    public OffsetDateTime getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(final OffsetDateTime effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    @Override
    public boolean equals(final Object other) {
        boolean equal = false;
        if (this == other) {
            equal = true;
        } else if (other instanceof QueryVersionId that) {
            equal = Objects.equals(queryId, that.queryId)
                    && Objects.equals(effectiveAt, that.effectiveAt);
        }
        return equal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryId, effectiveAt);
    }
}
