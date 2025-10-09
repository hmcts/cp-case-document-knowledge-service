package uk.gov.hmcts.cp.cdk.filters.audit.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record AuditPayload(
        ObjectNode content,
        String origin,
        String component,
        String timestamp
) {
    public static class Builder {
        private ObjectNode content;
        private String origin;
        private String component;
        private String timestamp;

        public Builder content(ObjectNode content) {
            this.content = content;
            return this;
        }

        public Builder origin(String origin) {
            this.origin = origin;
            return this;
        }

        public Builder component(String component) {
            this.component = component;
            return this;
        }

        public Builder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public AuditPayload build() {
            return new AuditPayload(content, origin, component, timestamp);
        }
    }
}
