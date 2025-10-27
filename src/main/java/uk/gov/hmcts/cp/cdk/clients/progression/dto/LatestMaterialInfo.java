package uk.gov.hmcts.cp.cdk.clients.progression.dto;

import java.util.List;

public record LatestMaterialInfo(
        List<String> caseIds,
        String documentTypeId,
        String documentTypeDescription,
        String materialId,
        java.time.ZonedDateTime uploadDateTime
) {}