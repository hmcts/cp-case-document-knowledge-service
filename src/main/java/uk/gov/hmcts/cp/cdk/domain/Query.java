package uk.gov.hmcts.cp.cdk.domain;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "queries")
public class Query {

    @Id
    @Column(name = "query_id", nullable = false)
    private UUID queryId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = utcNow();

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;
}
