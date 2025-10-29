package uk.gov.hmcts.cp.cdk.clients.hearing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProsecutionCaseSummaries(
        @JsonProperty("id") String prosecutionCaseId
) {
}