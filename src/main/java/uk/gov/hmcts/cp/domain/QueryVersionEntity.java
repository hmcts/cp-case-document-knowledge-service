package uk.gov.hmcts.cp.domain;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity mapping for the query_versions table.
 * Uses IngestionStatus enum mapped as STRING.
 *
 * Note: field name 'id' is intentionally preserved for compatibility with existing JPQL
 * which addresses q.id.effectiveAt in QueryVersionRepository.
 *
 * Suppress PMD short variable warnings because JPA expects the embedded id property to be named 'id'.
 */
@Entity
@Table(name = "query_versions")
@Access(AccessType.FIELD)
@SuppressWarnings("PMD.ShortVariable")
public class QueryVersionEntity implements Serializable {

    private static final long serialVersionUID = 1L;

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
        // JPA
    }

    public QueryVersionEntity(final QueryVersionKey id,
                              final String userQuery,
                              final String queryPrompt,
                              final IngestionStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userQuery = Objects.requireNonNull(userQuery, "userQuery must not be null");
        this.queryPrompt = queryPrompt;
        if (status != null) {
            this.status = status;
        }
    }

    /**
     * Factory method.
     */
    public static QueryVersionEntity create(final QueryVersionKey id,
                                            final String userQuery,
                                            final String queryPrompt,
                                            final IngestionStatus status) {
        return new QueryVersionEntity(id, userQuery, queryPrompt, status);
    }

    // --- Getters / Setters ---

    public QueryVersionKey getId() {
        return id;
    }

    public void setId(final QueryVersionKey id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(final String userQuery) {
        this.userQuery = Objects.requireNonNull(userQuery, "userQuery must not be null");
    }

    public String getQueryPrompt() {
        return queryPrompt;
    }

    public void setQueryPrompt(final String queryPrompt) {
        this.queryPrompt = queryPrompt;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public void setStatus(final IngestionStatus status) {
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
        final String idStr = (id == null) ? "null" : id.toString();
        return "QueryVersionEntity[id=" + idStr
                + ", userQuery=" + userQuery
                + ", queryPrompt=" + queryPrompt
                + ", status=" + status + "]";
    }
}
