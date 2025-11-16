package uk.gov.hmcts.cp.cdk.batch.clients.hearing.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProsecutionCaseSummaries(
        @JsonProperty("id") String prosecutionCaseId,
        @JsonProperty("defendants") List<DefendantSummary> defendants
) {
}