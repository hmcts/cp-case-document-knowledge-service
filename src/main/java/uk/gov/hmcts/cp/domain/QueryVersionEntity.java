package uk.gov.hmcts.cp.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity mapping for the query_versions table.
 * Uses IngestionStatus enum mapped as STRING.
 */
@Entity
@Table(name = "query_versions")
@Access(AccessType.FIELD)
public class QueryVersionEntity {

    @EmbeddedId
    private QueryVersionKey id;

    @Column(name = "user_query", nullable = false)
    private String userQuery;

    @Column(name = "query_prompt")
    private String queryPrompt;

    /**
     * Mapped as text (enum name) in DB. Default is UPLOADED.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IngestionStatus status = IngestionStatus.UPLOADED;

    protected QueryVersionEntity() {
    }

    public QueryVersionEntity(final QueryVersionKey id,
                              final String userQuery,
                              final String queryPrompt,
                              final IngestionStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userQuery = Objects.requireNonNull(userQuery, "userQuery must not be null");
        this.queryPrompt = queryPrompt;
        if (status != null) this.status = status;
    }

    public static QueryVersionEntity of(QueryVersionKey id, String userQuery, String queryPrompt, IngestionStatus status) {
        return new QueryVersionEntity(id, userQuery, queryPrompt, status);
    }

    // --- Getters / Setters ---

    public QueryVersionKey getId() {
        return id;
    }

    public void setId(QueryVersionKey id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = Objects.requireNonNull(userQuery, "userQuery must not be null");
    }

    public String getQueryPrompt() {
        return queryPrompt;
    }

    public void setQueryPrompt(String queryPrompt) {
        this.queryPrompt = queryPrompt;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public void setStatus(IngestionStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    // convenience accessors
    @Transient
    public UUID getQueryId() {
        return id == null ? null : id.getQueryId();
    }

    @Transient
    public Instant getEffectiveAt() {
        return id == null ? null : id.getEffectiveAt();
    }

    @Override
    public String toString() {
        var idStr = id == null ? "null" : id.toString();
        return "QueryVersionEntity[id=%s, userQuery=%s, queryPrompt=%s, status=%s]"
                .formatted(idStr, userQuery, queryPrompt, status);
    }
}
