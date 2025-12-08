package uk.gov.hmcts.cp.cdk.domain;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
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
@Embeddable
public class QueryVersionId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // Mapped via @MapsId/@JoinColumn on QueryVersion
    private UUID queryId;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private OffsetDateTime effectiveAt;
}
