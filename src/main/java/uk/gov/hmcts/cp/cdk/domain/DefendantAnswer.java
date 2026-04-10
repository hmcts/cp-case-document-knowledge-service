package uk.gov.hmcts.cp.cdk.domain;

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

/**
 * Answer generated per defendant for a case query.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "defendant_answers",
        indexes = {
                @Index(name = "idx_def_case_query_date_desc", columnList = "case_id,query_id,created_at DESC"),
                @Index(name = "idx_def_case_query_ver_desc", columnList = "case_id,query_id,version DESC")
        }
)
public class DefendantAnswer extends DocumentAnswer {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "caseId", column = @Column(name = "case_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "queryId", column = @Column(name = "query_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "defendantId", column = @Column(name = "defendant_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, updatable = false))
    })
    private DefendantAnswerId answerId;

}