package uk.gov.hmcts.cp.cdk.domain.progression;

import java.util.List;

public record LatestMaterialInfo(
        List<String> caseIds,
        String documentTypeId,
        String documentTypeDescription,
        String materialId,
        java.time.ZonedDateTime uploadDateTime
) {}