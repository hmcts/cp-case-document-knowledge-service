package uk.gov.hmcts.cp.cdk.batch.clients.common;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "cqrs.client")
public record CQRSClientProperties(
        @NotBlank String baseUrl,
        @Positive int connectTimeoutMs,
        @Positive int readTimeoutMs,
        Headers headers
) {
    public CQRSClientProperties {
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 3000;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = 15_000;
        }
        if (headers == null) {
            headers = new Headers(null);
        }
    }

    public Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs);
    }

    public Duration readTimeout() {
        return Duration.ofMillis(readTimeoutMs);
    }

    public record Headers(String cjsCppuid) {
        public Headers {
            if (cjsCppuid == null || cjsCppuid.isBlank()) {
                cjsCppuid = "CJSCPPUID";
            }
        }
    }
}
