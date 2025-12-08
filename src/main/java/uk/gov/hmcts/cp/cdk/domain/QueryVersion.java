package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
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
        name = "query_versions",
        indexes = @Index(name = "idx_qv_query_eff_desc", columnList = "query_id,effective_at DESC")
)
public class QueryVersion {

    @EmbeddedId
    private QueryVersionId queryVersionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("queryId")
    @JoinColumn(name = "query_id", nullable = false)
    private Query query;

    @Column(name = "user_query", nullable = false, columnDefinition = "TEXT")
    private String userQuery;

    @Column(name = "query_prompt", nullable = false, columnDefinition = "TEXT")
    private String queryPrompt;
}
