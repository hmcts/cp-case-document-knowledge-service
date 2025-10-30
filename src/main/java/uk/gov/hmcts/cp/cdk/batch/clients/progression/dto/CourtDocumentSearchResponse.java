package uk.gov.hmcts.cp.cdk.batch.clients.progression.dto;

import java.time.ZonedDateTime;
import java.util.List;

public record CourtDocumentSearchResponse(
        List<DocumentIndex> documentIndices
) {
    public record DocumentIndex(
            List<String> caseIds,
            Document document
    ) {
    }

    public record Document(
            String documentTypeId,
            String documentTypeDescription,
            List<Material> materials
    ) {
    }

    public record Material(
            String id,
            ZonedDateTime uploadDateTime
    ) {
    }
}