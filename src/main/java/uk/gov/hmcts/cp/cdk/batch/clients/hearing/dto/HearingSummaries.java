package uk.gov.hmcts.cp.cdk.batch.clients.hearing.dto;

import java.util.List;

public record HearingSummaries(
        List<ProsecutionCaseSummaries> prosecutionCaseSummaries
) {
}