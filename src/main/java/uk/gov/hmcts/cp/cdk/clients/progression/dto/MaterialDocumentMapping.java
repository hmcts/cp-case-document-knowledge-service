package uk.gov.hmcts.cp.cdk.clients.progression.dto;

import java.io.Serializable;

public record MaterialDocumentMapping(
        String materialId,
        String materialName,
        String caseId,
        String resolvedDocId,
        String existingDocId,
        String newDocId
) implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean isNewUpload() {
        return existingDocId == null && newDocId != null;
    }
}