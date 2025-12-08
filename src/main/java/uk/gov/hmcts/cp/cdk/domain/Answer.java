package uk.gov.hmcts.cp.cdk.domain;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "answers",
        indexes = {
                @Index(name = "idx_ans_case_query_date_desc", columnList = "case_id,query_id,created_at DESC"),
                @Index(name = "idx_ans_case_query_ver_desc", columnList = "case_id,query_id,version DESC")
        }
)
public class Answer {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "caseId", column = @Column(name = "case_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "queryId", column = @Column(name = "query_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, updatable = false))
    })
    private AnswerId answerId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = utcNow();

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "llm_input", columnDefinition = "TEXT")
    private String llmInput;

    @Column(name = "doc_id")
    private UUID docId;
}
