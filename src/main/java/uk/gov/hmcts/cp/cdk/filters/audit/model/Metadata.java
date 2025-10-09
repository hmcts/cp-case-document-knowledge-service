package uk.gov.hmcts.cp.cdk.filters.audit.model;

import java.util.Optional;
import java.util.UUID;

public record Metadata(
        UUID id,
        String name,
        String createdAt,
        Optional<Correlation> correlation,
        Optional<Context> context
) {
    public static class Builder {
        private UUID id;
        private String name;
        private String createdAt;
        private Optional<Correlation> correlation = Optional.empty();
        private Optional<Context> context = Optional.empty();

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder createdAt(String createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder correlation(Correlation correlation) {
            this.correlation = Optional.ofNullable(correlation);
            return this;
        }

        public Builder context(Context context) {
            this.context = Optional.ofNullable(context);
            return this;
        }

        public Metadata build() {
            return new Metadata(id, name, createdAt, correlation, context);
        }
    }

    public record Correlation(String client) {
    }

    public record Context(String user) {
    }
}
