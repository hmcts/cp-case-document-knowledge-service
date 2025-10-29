package uk.gov.hmcts.cp.cdk.clients.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly-typed properties for the CQRS/CDK client.
 * Bind from: cqrs.client.*
 */
@Validated
@ConfigurationProperties(prefix = "cqrs.client")
public record CQRSClientProperties(
        @NotBlank String baseUrl,
        @Positive int connectTimeoutMs,
        @Positive int readTimeoutMs,
        Headers headers
) {
    public CQRSClientProperties {
        // Defaults
        if (connectTimeoutMs <= 0) connectTimeoutMs = 3000;
        if (readTimeoutMs <= 0) readTimeoutMs = 15000;
        if (headers == null) headers = new Headers(null);
    }

    public Duration connectTimeout() { return Duration.ofMillis(connectTimeoutMs); }
    public Duration readTimeout()    { return Duration.ofMillis(readTimeoutMs); }

    /**
     * Nested, typed headers block. Add more fields here as needed.
     */
    public record Headers(String cjsCppuid) {
        public Headers {
            if (cjsCppuid == null || cjsCppuid.isBlank()) cjsCppuid = "CJSCPPUID";
        }
    }
}
