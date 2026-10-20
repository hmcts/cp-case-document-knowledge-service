package uk.gov.hmcts.cp.cdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding tests for the new {@code SchedulerProperties.*.enabled} field (Story 2, AC-005).
 *
 * <p>Two deliberately different scenarios, per {@code 04-test-specs.md} Scenario 2.5 — proving
 * only one of them ("hand-fed false binds to false") would be a weaker test that cannot detect a
 * regression to the shipped default.
 */
@DisplayName("SchedulerProperties enabled-field binding tests")
class SchedulerPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerPropertiesConfig.class);

    @Test
    @DisplayName("enabled defaults to true when the property is absent entirely, "
            + "mirroring @ConditionalOnProperty(matchIfMissing = true)")
    void enabled_shouldDefaultToTrue_whenPropertyIsAbsentEntirely() {
        contextRunner.run(context -> {
            final SchedulerProperties properties = context.getBean(SchedulerProperties.class);
            assertThat(properties.getIntradayDiscovery().isEnabled()).isTrue();
            assertThat(properties.getNightlyDiscovery().isEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("the shipped application-cdk.yml binds enabled=false for both schedulers "
            + "when CP_CDK_SCHEDULER_*_ENABLED are unset (the load-bearing assertion)")
    void enabled_shouldBindFalse_fromTheShippedApplicationCdkYaml_whenEnvVarsAreUnset() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/application-cdk.yml")
                .run(context -> {
                    final SchedulerProperties properties = context.getBean(SchedulerProperties.class);
                    assertThat(properties.getIntradayDiscovery().isEnabled())
                            .as("shipped default for CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED")
                            .isFalse();
                    assertThat(properties.getNightlyDiscovery().isEnabled())
                            .as("shipped default for CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("nightly-discovery stagger binds enabled / cases-per-minute / max-window when supplied "
            + "(DD-43823 Option A1)")
    void stagger_shouldBindAllFields_whenProvided() {
        contextRunner
                .withPropertyValues(
                        "scheduler.nightly-discovery.stagger.enabled=true",
                        "scheduler.nightly-discovery.stagger.cases-per-minute=4",
                        "scheduler.nightly-discovery.stagger.max-window=PT6H")
                .run(context -> {
                    final SchedulerProperties.NightlyDiscovery.Stagger stagger =
                            context.getBean(SchedulerProperties.class).getNightlyDiscovery().getStagger();
                    assertThat(stagger.isEnabled()).isTrue();
                    assertThat(stagger.getCasesPerMinute()).isEqualTo(4.0);
                    assertThat(stagger.getMaxWindow()).isEqualTo(Duration.ofHours(6));
                });
    }

    @Test
    @DisplayName("nightly-discovery stagger defaults to disabled, 2 cases/min, PT8H when the block is absent")
    void stagger_shouldUseSafeDefaults_whenBlockAbsent() {
        contextRunner.run(context -> {
            final SchedulerProperties.NightlyDiscovery.Stagger stagger =
                    context.getBean(SchedulerProperties.class).getNightlyDiscovery().getStagger();
            assertThat(stagger).isNotNull();
            assertThat(stagger.isEnabled()).isFalse();
            assertThat(stagger.getCasesPerMinute()).isEqualTo(2.0);
            assertThat(stagger.getMaxWindow()).isEqualTo(Duration.ofHours(8));
        });
    }

    @Configuration
    @EnableConfigurationProperties(SchedulerProperties.class)
    static class SchedulerPropertiesConfig {
    }
}
