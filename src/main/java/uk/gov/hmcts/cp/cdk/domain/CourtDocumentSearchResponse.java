package uk.gov.hmcts.cp.cdk.domain;

import java.util.List;
import java.time.ZonedDateTime;


public record CourtDocumentSearchResponse(
        List<DocumentIndex> documentIndices
) {

    public record DocumentIndex(
            List<String> caseIds,
            Document document
    ) {}

    public record Document(
            String documentTypeId,
            String documentTypeDescription,
            List<Material> materials
    ) {}

    public record Material(
            String id,
            ZonedDateTime uploadDateTime
    ) {}
}


