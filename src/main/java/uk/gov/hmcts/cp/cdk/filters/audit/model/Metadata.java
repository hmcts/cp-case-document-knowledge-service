package uk.gov.hmcts.cp.cdk.filters.audit.model;

import lombok.Builder;

import java.util.Optional;
import java.util.UUID;

@Builder
public record Metadata(
        UUID id,
        String name,
        String createdAt,
        Optional<Correlation> correlation,
        Optional<Context> context
) {

    public record Correlation(String client) {
    }

    public record Context(String user) {
    }
}
