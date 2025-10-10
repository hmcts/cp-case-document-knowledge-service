package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "answer")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AnswerNewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long answerId;

    @NotNull
    private UUID caseId;
    @NotNull
    private UUID queryId;
    @NotNull
    private Long version;

    private String answer;
    private String llmInput;
    private UUID docId;

    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
