package uk.gov.hmcts.cp.cdk.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@Embeddable
public class CaseQueryStatusId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "query_id", nullable = false, updatable = false)
    private UUID queryId;
}
