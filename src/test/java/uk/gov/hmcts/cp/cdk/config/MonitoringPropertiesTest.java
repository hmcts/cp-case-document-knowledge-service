package uk.gov.hmcts.cp.cdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding tests for {@code cdk.monitoring.*} (Story 4, AC-005, AC-010, AC-011).
 */
@DisplayName("MonitoringProperties binding tests")
class MonitoringPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MonitoringPropertiesConfig.class);

    @Test
    @DisplayName("a unit-less integer threshold binds as minutes, not milliseconds")
    void unitLessIntegerThreshold_bindsAsMinutes() {
        contextRunner
                .withPropertyValues("cdk.monitoring.stalled-threshold=45")
                .run(context -> {
                    final MonitoringProperties properties = context.getBean(MonitoringProperties.class);
                    assertThat(properties.getStalledThreshold()).isEqualTo(Duration.ofMinutes(45));
                });
    }

    @Test
    @DisplayName("the shipped application-cdk.yml defaults satisfy FR-004's >=60s refresh floor "
            + "and lock-at-most-for > refresh-interval and > the PT30S ShedLock global default")
    void shippedDefaults_satisfyFr004Floor() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/application-cdk.yml")
                .run(context -> {
                    // Assert against the raw Environment property, not just the bound object: a
                    // `new MonitoringProperties()` field initialiser produces the identical
                    // Duration.ofMinutes(30) value, so a test that only inspects the bound object
                    // would still pass even if application-cdk.yml failed to load entirely. The
                    // Environment property is only present if the YAML's
                    // `${CP_CDK_MONITORING_STALLED_THRESHOLD:PT30M}` placeholder was actually
                    // resolved from the file — it is null if the file never loaded.
                    assertThat(context.getEnvironment().getProperty("cdk.monitoring.stalled-threshold"))
                            .as("proves application-cdk.yml was actually loaded, not just that the "
                                    + "Java field initialiser happens to match")
                            .isEqualTo("PT30M");

                    final MonitoringProperties properties = context.getBean(MonitoringProperties.class);

                    assertThat(properties.getRefreshInterval())
                            .as("AC-010: refresh cadence must not be shorter than 60s")
                            .isGreaterThanOrEqualTo(Duration.ofSeconds(60));
                    assertThat(properties.getLockAtMostFor())
                            .as("AC-011: lockAtMostFor must exceed the refresh cadence")
                            .isGreaterThan(properties.getRefreshInterval());
                    assertThat(properties.getLockAtMostFor())
                            .as("AC-011: lockAtMostFor must exceed ShedLockConfig's PT30S global default")
                            .isGreaterThan(Duration.ofSeconds(30));
                    assertThat(properties.isEnabled())
                            .as("ADR-002: the refresh defaults ON, unlike the discovery schedulers")
                            .isTrue();

                    // N-6 (Scenario 4.5): the two assertions below were missing. Both are bound
                    // straight from application-cdk.yml, not from the Java field initialisers.
                    assertThat(properties.getStalledThreshold())
                            .as("shipped stalled-threshold default is PT30M")
                            .isEqualTo(Duration.ofMinutes(30));
                    assertThat(properties.getInitialDelay())
                            .as("shipped initial-delay default is PT30S")
                            .isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.getLockAtLeastFor().getSeconds())
                            .as("ADR-002 / MonitoringConfig's coupling rule: lock-at-least-for must "
                                    + "be at least 0.9x refresh-interval, or more than one pod could "
                                    + "refresh within the same cadence (ADR-008)")
                            .isGreaterThanOrEqualTo(
                                    (long) (properties.getRefreshInterval().getSeconds() * 0.9));
                });
    }

    @Configuration
    @EnableConfigurationProperties(MonitoringProperties.class)
    static class MonitoringPropertiesConfig {
    }
}
