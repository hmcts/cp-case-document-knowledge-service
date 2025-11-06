package uk.gov.hmcts.cp.cdk.batch.clients.progression.dto;

import java.io.Serializable;

public record MaterialMetaData(
        String materialId,
        String materialName
) implements Serializable {
    private static final long serialVersionUID = 1L;
}