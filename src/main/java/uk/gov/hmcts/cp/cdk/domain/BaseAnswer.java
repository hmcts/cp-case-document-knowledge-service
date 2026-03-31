package uk.gov.hmcts.cp.cdk.domain;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@Getter
@Setter
public class BaseAnswer {
    @Column(name = "created_at", nullable = false)
    protected OffsetDateTime createdAt = utcNow();

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    protected String answerText;

    @Column(name = "llm_input", columnDefinition = "TEXT")
    protected String llmInput;
}
