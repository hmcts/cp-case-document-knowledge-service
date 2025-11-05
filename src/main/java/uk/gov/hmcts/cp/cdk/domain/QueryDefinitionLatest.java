package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "v_query_definitions_latest")
public class QueryDefinitionLatest {

    @Id
    @Column(name = "query_id", nullable = false)
    private UUID queryId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "effective_at", nullable = false)
    private OffsetDateTime effectiveAt;

    @Column(name = "user_query", nullable = false)
    private String userQuery;

    @Column(name = "query_prompt", nullable = false)
    private String queryPrompt;

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

    public OffsetDateTime getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(final OffsetDateTime effectiveAt) {
        this.effectiveAt = effectiveAt;
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
}
