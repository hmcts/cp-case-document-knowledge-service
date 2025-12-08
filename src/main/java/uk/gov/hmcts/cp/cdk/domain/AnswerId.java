package uk.gov.hmcts.cp.cdk.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

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
public class AnswerId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private UUID caseId;
    private UUID queryId;
    private Integer version;

}
