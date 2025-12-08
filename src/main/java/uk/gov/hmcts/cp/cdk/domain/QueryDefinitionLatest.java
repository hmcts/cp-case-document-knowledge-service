package uk.gov.hmcts.cp.cdk.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "v_query_definitions_latest")
public class QueryDefinitionLatest {

    @Id
    @Column(name = "query_id", nullable = false)
    private UUID queryId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "effective_at", nullable = false)
    private OffsetDateTime effectiveAt;

    @Column(name = "user_query", nullable = false)
    private String userQuery;

    @Column(name = "query_prompt", nullable = false)
    private String queryPrompt;
}
