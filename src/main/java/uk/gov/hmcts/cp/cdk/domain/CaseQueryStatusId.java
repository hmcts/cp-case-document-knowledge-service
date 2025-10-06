package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class CaseQueryStatusId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "query_id", nullable = false, updatable = false)
    private UUID queryId;

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(final UUID caseId) {
        this.caseId = caseId;
    }

    public UUID getQueryId() {
        return queryId;
    }

    public void setQueryId(final UUID queryId) {
        this.queryId = queryId;
    }

    @Override
    public boolean equals(final Object other) {
        boolean equal = false;
        if (this == other) {
            equal = true;
        } else if (other instanceof CaseQueryStatusId that) {
            equal = Objects.equals(caseId, that.caseId)
                    && Objects.equals(queryId, that.queryId);
        }
        return equal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(caseId, queryId);
    }
}
