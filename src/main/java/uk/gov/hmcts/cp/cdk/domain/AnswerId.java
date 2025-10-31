package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.Embeddable;
import lombok.Builder;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
@Builder
public class AnswerId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private UUID caseId;
    private UUID queryId;
    private Integer version;

    public AnswerId() {
        // JPA
    }

    public AnswerId(final UUID caseId, final UUID queryId, final Integer version) {
        this.caseId = caseId;
        this.queryId = queryId;
        this.version = version;
    }

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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(final Integer version) {
        this.version = version;
    }

    @Override
    public boolean equals(final Object other) {
        boolean equal = false;
        if (this == other) {
            equal = true;
        } else if (other instanceof AnswerId that) {
            equal = Objects.equals(caseId, that.caseId)
                    && Objects.equals(queryId, that.queryId)
                    && Objects.equals(version, that.version);
        }
        return equal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(caseId, queryId, version);
    }
}
