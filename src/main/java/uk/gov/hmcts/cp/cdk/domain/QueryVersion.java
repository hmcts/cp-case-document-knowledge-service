package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(
        name = "query_versions",
        indexes = @Index(name = "idx_qv_query_eff_desc", columnList = "query_id,effective_at DESC")
)
public class QueryVersion {

    @EmbeddedId
    private QueryVersionId queryVersionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("queryId")
    @JoinColumn(name = "query_id", nullable = false)
    private Query query;

    @Column(name = "user_query", nullable = false, columnDefinition = "TEXT")
    private String userQuery;

    @Column(name = "query_prompt", nullable = false, columnDefinition = "TEXT")
    private String queryPrompt;

    public QueryVersionId getQueryVersionId() {
        return queryVersionId;
    }

    public void setQueryVersionId(final QueryVersionId queryVersionId) {
        this.queryVersionId = queryVersionId;
    }

    public Query getQuery() {
        return query;
    }

    public void setQuery(final Query query) {
        this.query = query;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(final String userQuery) {
        this.userQuery = userQuery;
    }

    public String getQueryPrompt() {
        return queryPrompt;
    }

    public void setQueryPrompt(final String queryPrompt) {
        this.queryPrompt = queryPrompt;
    }

    @Override
    public boolean equals(final Object other) {
        boolean equal = false;
        if (this == other) {
            equal = true;
        } else if (other instanceof QueryVersion that) {
            equal = Objects.equals(queryVersionId, that.queryVersionId);
        }
        return equal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryVersionId);
    }
}
