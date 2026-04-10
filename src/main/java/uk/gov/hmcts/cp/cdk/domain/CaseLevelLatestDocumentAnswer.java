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
 * Case-level answer generated from the latest document only.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "case_level_latest_doc_answers",
        indexes = {
                @Index(name = "idx_cl_latest_case_query_date_desc", columnList = "case_id,query_id,created_at DESC"),
                @Index(name = "idx_cl_latest_case_query_ver_desc", columnList = "case_id,query_id,version DESC")
        }
)
public class CaseLevelLatestDocumentAnswer extends DocumentAnswer{

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "caseId", column = @Column(name = "case_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "queryId", column = @Column(name = "query_id", nullable = false, updatable = false)),
            @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, updatable = false))
    })
    private AnswerId answerId;

}