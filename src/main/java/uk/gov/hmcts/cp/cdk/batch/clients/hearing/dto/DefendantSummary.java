package uk.gov.hmcts.cp.cdk.batch.clients.hearing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

@SuppressWarnings("PMD.ShortVariable")
public record DefendantSummary(
        @JsonProperty("id") String id
) {
}
