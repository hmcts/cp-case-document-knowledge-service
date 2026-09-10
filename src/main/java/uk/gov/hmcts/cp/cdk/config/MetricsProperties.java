package uk.gov.hmcts.cp.cdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code cdk.metrics.*} (DD-43182, ADR-010). Gates <em>recording</em> only — every meter this
 * ticket adds is still registered at value {@code 0} regardless of this flag (DD-43185 ADR-002
 * point 6), so a scrape's series shape never depends on it. Default {@code true}: recording is
 * in-process and side-effect-free, so defaulting it off would reproduce the "pod looks healthy and
 * silently publishes nothing" failure mode DD-43182 exists to remove.
 */
@ConfigurationProperties(prefix = "cdk.metrics")
public class MetricsProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }
}
