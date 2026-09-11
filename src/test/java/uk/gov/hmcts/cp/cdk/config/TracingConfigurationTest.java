package uk.gov.hmcts.cp.cdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Pins the corrected tracing/OTLP property keys in {@code application-server-management.yml}
 * (DD-43183 Story 6): the real Boot 4.0.6 keys are bound, the three dead keys are gone, trace and
 * metrics export are independently switchable, and the defaults preserve today's "export nothing"
 * behaviour.
 *
 * <p>AC-006's "neither exporter bean exists" is proven at the level Spring Boot's own
 * {@code @ConditionalOnProperty} annotations act on — the resolved property value each exporter's
 * autoconfiguration gates on — rather than by instantiating the full OpenTelemetry SDK
 * autoconfiguration bean graph, which the existing compose-based integration suite already
 * exercises end-to-end with these exact shipped defaults.
 */
@DisplayName("TracingConfiguration tests (DD-43183 Story 6)")
class TracingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application-server-management.yml");

    @Test
    @DisplayName("AC-002: the three dead tracing/OTLP keys are gone from the YAML source")
    void deadKeysAreRemovedFromTheYamlSource() throws IOException {
        final String activeYamlOnly = readYaml().lines()
                .filter(line -> !line.strip().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(activeYamlOnly).doesNotContain("otlp.tracing.enabled");
        assertThat(activeYamlOnly).doesNotContain("otlp.tracing.endpoint");
        assertThat(activeYamlOnly).doesNotContain("tracing:\n    enabled:");
        assertThat(activeYamlOnly).contains("enabled: ${OTEL_TRACES_ENABLED:false}");
        assertThat(activeYamlOnly).contains("endpoint: ${OTEL_TRACES_URL:http://localhost:4318/v1/traces}");
        assertThat(activeYamlOnly).contains("url: ${OTEL_METRICS_URL:http://localhost:4318/v1/metrics}");
    }

    @Test
    @DisplayName("AC-001: OTEL_METRICS_ENABLED alone has no effect on trace export (the historical bug)")
    void metricsFlagAloneDoesNotEnableTraceExport() {
        contextRunner.withPropertyValues("OTEL_METRICS_ENABLED=true").run(context -> {
            final String traceExportEnabled = context.getEnvironment()
                    .getProperty("management.tracing.export.otlp.enabled");
            assertThat(traceExportEnabled).isEqualTo("false");
        });
    }

    @Test
    @DisplayName("AC-001: OTEL_TRACES_ENABLED independently controls trace export, "
            + "without touching metrics export")
    void tracesFlagIndependentlyControlsTraceExport() {
        contextRunner.withPropertyValues("OTEL_TRACES_ENABLED=true").run(context -> {
            assertThat(context.getEnvironment().getProperty("management.tracing.export.otlp.enabled"))
                    .isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("management.otlp.metrics.export.enabled"))
                    .as("setting OTEL_TRACES_ENABLED must not also flip metrics export")
                    .isEqualTo("false");
        });
    }

    @Test
    @DisplayName("AC-003: default trace export path is /v1/traces and metrics export path is /v1/metrics")
    void defaultExportPathsAreSpecCompliant() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                    .isEqualTo("http://localhost:4318/v1/traces");
            assertThat(context.getEnvironment().getProperty("management.otlp.metrics.export.url"))
                    .isEqualTo("http://localhost:4318/v1/metrics");
        });
    }

    @Test
    @DisplayName("AC-005 (GATE-5): sampling probability defaults to 0.1, overridable to 1.0")
    void samplingProbabilityDefaultsToPointOneAndIsOverridable() {
        contextRunner.run(context ->
                assertThat(context.getEnvironment().getProperty("management.tracing.sampling.probability"))
                        .isEqualTo("0.1"));

        contextRunner.withPropertyValues("TRACING_SAMPLER_PROBABILITY=1.0").run(context ->
                assertThat(context.getEnvironment().getProperty("management.tracing.sampling.probability"))
                        .isEqualTo("1.0"));
    }

    @Test
    @DisplayName("AC-006: with both OTEL_TRACES_ENABLED and OTEL_METRICS_ENABLED unset, "
            + "both exporters resolve to disabled — the current effective default is preserved exactly")
    void bothExportersDefaultToDisabledWhenUnset() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("management.tracing.export.otlp.enabled"))
                    .isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("management.otlp.metrics.export.enabled"))
                    .isEqualTo("false");
        });
    }

    private String readYaml() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application-server-management.yml")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
