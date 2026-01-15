package uk.gov.hmcts.cp.cdk.clients.hearing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

@SuppressWarnings("PMD.ShortVariable")
public record DefendantSummary(
        @JsonProperty("id") String id
) {
}
