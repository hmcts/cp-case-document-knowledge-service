package uk.gov.hmcts.cp.cdk.clients.documentstatus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO from the DocumentStatusCheck function app.
 * Matches the DocumentIngestionOutcome structure from Azure Table Storage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentStatusResponse(
        @JsonProperty("documentId") String documentId,
        @JsonProperty("documentName") String documentName,
        @JsonProperty("status") String status,
        @JsonProperty("reason") String reason,
        @JsonProperty("timestamp") String timestamp
) {
}

