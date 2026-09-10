package uk.gov.hmcts.cp.cdk.metrics;

import uk.gov.hmcts.cp.cdk.config.MetricsProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link MetricsProperties} (DD-43182, ADR-010) — the {@code cdk.metrics.enabled} kill
 * switch consumed by {@code TaskRetryMetricsAspect}'s {@code @ConditionalOnProperty} and by every
 * metrics-recording call site via {@link MetricsSafety}'s callers.
 */
@Configuration
@EnableConfigurationProperties(MetricsProperties.class)
public class CdkMetricsConfig {
}
