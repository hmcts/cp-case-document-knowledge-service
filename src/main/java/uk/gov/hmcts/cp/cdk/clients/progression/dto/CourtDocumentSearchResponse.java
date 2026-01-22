package uk.gov.hmcts.cp.cdk.clients.progression.dto;

import java.time.ZonedDateTime;
import java.util.List;

@SuppressWarnings("PMD.ShortVariable")
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
            String name,
            String courtDocumentId,
            List<Material> materials
    ) {
    }

    public record Material(
            String id,
            ZonedDateTime uploadDateTime
    ) {
    }
}