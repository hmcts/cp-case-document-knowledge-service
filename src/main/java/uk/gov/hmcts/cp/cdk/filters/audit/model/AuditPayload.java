package uk.gov.hmcts.cp.cdk.filters.audit.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;

@Builder
public record AuditPayload(
        ObjectNode content,
        String origin,
        String component,
        String timestamp,
        Metadata _metadata

) {
}