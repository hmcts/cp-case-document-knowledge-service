package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "answers",
        indexes = {
                @Index(name = "idx_ans_case_query_date_desc", columnList = "case_id,query_id,created_at DESC"),
                @Index(name = "idx_ans_case_query_ver_desc", columnList = "case_id,query_id,version DESC")
        }
)
@Builder
public class Answer {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "caseId", column = @Column(name = "case_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "queryId", column = @Column(name = "query_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, updatable = false))
    })
    private AnswerId answerId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default()
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "llm_input", columnDefinition = "TEXT")
    private String llmInput;

    @Column(name = "doc_id")
    private UUID docId;

    public AnswerId getAnswerId() {
        return answerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getAnswerText() {
        return answerText;
    }

    public String getLlmInput() {
        return llmInput;
    }

    public UUID getDocId() {
        return docId;
    }

    @Override
    public boolean equals(final Object other) {
        boolean equal = false;
        if (this == other) {
            equal = true;
        } else if (other instanceof Answer that) {
            equal = Objects.equals(answerId, that.answerId);
        }
        return equal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(answerId);
    }
}
