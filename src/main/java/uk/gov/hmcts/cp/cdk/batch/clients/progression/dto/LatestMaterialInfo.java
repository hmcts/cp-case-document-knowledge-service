package uk.gov.hmcts.cp.cdk.batch.clients.progression.dto;

import java.time.ZonedDateTime;
import java.util.List;

public record LatestMaterialInfo(
        List<String> caseIds,
        String documentTypeId,
        String documentTypeDescription,
        String materialId,
        String materialName,
        ZonedDateTime uploadDateTime
) {
}