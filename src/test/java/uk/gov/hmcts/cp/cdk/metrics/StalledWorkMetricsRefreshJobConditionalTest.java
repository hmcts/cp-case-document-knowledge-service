package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import uk.gov.hmcts.cp.cdk.config.MonitoringProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.CaseQueryStatusRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DD-43185 Scenario 4.10 (second half) / ADR-002 (6): {@code cdk.monitoring.enabled} must gate
 * only the scheduled {@link StalledWorkMetricsRefreshJob}, never {@link StalledWorkMetrics}'s
 * meter registration — every series must always exist (at its last-known value), so a disabled
 * refresh is visible as stale values, not as missing series.
 */
@DisplayName("StalledWorkMetrics / StalledWorkMetricsRefreshJob conditional registration tests")
class StalledWorkMetricsRefreshJobConditionalTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MockDependenciesConfig.class, StalledWorkMetrics.class,
                    StalledWorkMetricsRefreshJob.class);

    @Test
    @DisplayName("cdk.monitoring.enabled=false: StalledWorkMetrics stays registered, "
            + "StalledWorkMetricsRefreshJob is absent")
    void refreshJobAbsent_metricsPresent_whenDisabled() {
        contextRunner
                .withPropertyValues("cdk.monitoring.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(StalledWorkMetrics.class);
                    assertThat(context).doesNotHaveBean(StalledWorkMetricsRefreshJob.class);
                });
    }

    @Test
    @DisplayName("cdk.monitoring.enabled unset (matchIfMissing=true): both beans are registered")
    void bothPresent_byDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StalledWorkMetrics.class);
            assertThat(context).hasSingleBean(StalledWorkMetricsRefreshJob.class);
        });
    }

    @Test
    @DisplayName("cdk.monitoring.enabled=true: both beans are registered")
    void bothPresent_whenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("cdk.monitoring.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(StalledWorkMetrics.class);
                    assertThat(context).hasSingleBean(StalledWorkMetricsRefreshJob.class);
                });
    }

    @Configuration
    @EnableConfigurationProperties(MonitoringProperties.class)
    static class MockDependenciesConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        CaseDocumentRepository caseDocumentRepository() {
            return mock(CaseDocumentRepository.class);
        }

        @Bean
        CaseQueryStatusRepository caseQueryStatusRepository() {
            return mock(CaseQueryStatusRepository.class);
        }
    }
}
