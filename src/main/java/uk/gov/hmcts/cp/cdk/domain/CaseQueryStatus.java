package uk.gov.hmcts.cp.cdk.domain;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "case_query_status",
        indexes = {
                @Index(name = "idx_cqs_case_status", columnList = "case_id,status"),
                @Index(name = "idx_cqs_status_at_desc", columnList = "status_at DESC")
        }
)
public class CaseQueryStatus {

    @EmbeddedId
    private CaseQueryStatusId caseQueryStatusId = new CaseQueryStatusId();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(
            name = "status",
            nullable = false,
            columnDefinition = "query_lifecycle_status_enum"
    )
    private QueryLifecycleStatus status = QueryLifecycleStatus.ANSWER_NOT_AVAILABLE;

    @Column(name = "status_at", nullable = false)
    private OffsetDateTime statusAt = utcNow();

    @Column(name = "doc_id")
    private UUID docId;

    @Column(name = "last_answer_version")
    private Integer lastAnswerVersion;

    @Column(name = "last_answer_at")
    private OffsetDateTime lastAnswerAt;

    public UUID getCaseId() {
        return caseQueryStatusId != null ? caseQueryStatusId.getCaseId() : null;
    }

    public void setCaseId(final UUID caseId) {
        if (this.caseQueryStatusId == null) {
            this.caseQueryStatusId = new CaseQueryStatusId();
        }
        this.caseQueryStatusId.setCaseId(caseId);
    }

    public UUID getQueryId() {
        return caseQueryStatusId != null ? caseQueryStatusId.getQueryId() : null;
    }

    public void setQueryId(final UUID queryId) {
        if (this.caseQueryStatusId == null) {
            this.caseQueryStatusId = new CaseQueryStatusId();
        }
        this.caseQueryStatusId.setQueryId(queryId);
    }

    public CaseQueryStatusId getCaseQueryStatusId() {
        return caseQueryStatusId;
    }

    public void setCaseQueryStatusId(final CaseQueryStatusId caseQueryStatusId) {
        this.caseQueryStatusId = caseQueryStatusId;
    }

    public QueryLifecycleStatus getStatus() {
        return status;
    }

    public void setStatus(final QueryLifecycleStatus status) {
        this.status = status;
    }

    public OffsetDateTime getStatusAt() {
        return statusAt;
    }

    public void setStatusAt(final OffsetDateTime statusAt) {
        this.statusAt = statusAt;
    }

    public UUID getDocId() {
        return docId;
    }

    public void setDocId(final UUID docId) {
        this.docId = docId;
    }

    public Integer getLastAnswerVersion() {
        return lastAnswerVersion;
    }

    public void setLastAnswerVersion(final Integer lastAnswerVersion) {
        this.lastAnswerVersion = lastAnswerVersion;
    }

    public OffsetDateTime getLastAnswerAt() {
        return lastAnswerAt;
    }

    public void setLastAnswerAt(final OffsetDateTime lastAnswerAt) {
        this.lastAnswerAt = lastAnswerAt;
    }
}
