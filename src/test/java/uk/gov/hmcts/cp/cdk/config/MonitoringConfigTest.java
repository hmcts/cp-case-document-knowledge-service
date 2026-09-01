package uk.gov.hmcts.cp.cdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("MonitoringConfig tests")
class MonitoringConfigTest {

    @Test
    @DisplayName("warns when refresh-interval is below FR-004's 60s floor")
    void warnsWhenRefreshIntervalBelowFloor() {
        final MonitoringProperties properties = new MonitoringProperties();
        properties.setRefreshInterval(Duration.ofSeconds(10));
        properties.setLockAtLeastFor(Duration.ofSeconds(9));

        final List<ILoggingEvent> warnings = captureWarnings(properties);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).contains("refresh-interval");
    }

    @Test
    @DisplayName("warns when lock-at-least-for is short relative to refresh-interval "
            + "(< 0.9x), even though refresh-interval itself is at or above the floor")
    void warnsWhenLockAtLeastForTooShortRelativeToRefreshInterval() {
        final MonitoringProperties properties = new MonitoringProperties();
        properties.setRefreshInterval(Duration.ofSeconds(60));
        properties.setLockAtLeastFor(Duration.ofSeconds(10));

        final List<ILoggingEvent> warnings = captureWarnings(properties);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).contains("lock-at-least-for");
    }

    @Test
    @DisplayName("logs no warnings when Java field-initialiser defaults are used")
    void noWarnings_whenFieldDefaultsSatisfyTheCouplingRule() {
        final List<ILoggingEvent> warnings = captureWarnings(new MonitoringProperties());

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("logs no warnings when application-cdk.yml's actual shipped values are bound "
            + "(N-6: distinct from the field-initialiser test above — this one fails if the YAML "
            + "ever drifted out of sync with the Java defaults, which the other test cannot detect)")
    void noWarnings_whenShippedYamlDefaultsAreUsed() {
        final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ShippedMonitoringConfig.class)
                .withPropertyValues("spring.config.location=classpath:/application-cdk.yml");

        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("cdk.monitoring.refresh-interval"))
                    .as("proves application-cdk.yml was actually loaded")
                    .isEqualTo("PT1M");

            final MonitoringConfig config = context.getBean(MonitoringConfig.class);
            final Logger logger = (Logger) LoggerFactory.getLogger(MonitoringConfig.class);
            final ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            try {
                config.validateShippedShapeAtStartup();
                assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList())
                        .isEmpty();
            } finally {
                logger.detachAppender(appender);
            }
        });
    }

    private List<ILoggingEvent> captureWarnings(final MonitoringProperties properties) {
        final MonitoringConfig config = new MonitoringConfig(properties);

        final Logger logger = (Logger) LoggerFactory.getLogger(MonitoringConfig.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            config.validateShippedShapeAtStartup();
            return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Configuration
    @EnableConfigurationProperties(MonitoringProperties.class)
    static class ShippedMonitoringConfig {

        @Bean
        MonitoringConfig monitoringConfig(final MonitoringProperties properties) {
            return new MonitoringConfig(properties);
        }
    }
}
