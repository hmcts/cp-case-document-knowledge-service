package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the scheduler run-outcome and heartbeat series (Story 1, DD-43218) are actually
 * observable on a live {@code /actuator/prometheus} scrape, not merely registered in-process.
 *
 * <p>The docker-compose integration stack enables both discovery schedulers with a 30-second
 * cron override, so at least one scheduled run is guaranteed within the Awaitility window.
 */
class SchedulerMetricsHttpLiveTest extends AbstractHttpLiveTest {

    private static final Pattern RUNS_SUCCESS_INTRADAY = Pattern.compile(
            "^cdk_scheduler_runs_total\\{[^}]*outcome=\"success\"[^}]*scheduler=\"intraday-discovery\"[^}]*}\\s+"
                    + "(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern RUNS_SUCCESS_NIGHTLY = Pattern.compile(
            "^cdk_scheduler_runs_total\\{[^}]*outcome=\"success\"[^}]*scheduler=\"nightly-discovery\"[^}]*}\\s+"
                    + "(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern HEARTBEAT_INTRADAY = Pattern.compile(
            "^cdk_scheduler_last_success_epoch_seconds\\{[^}]*scheduler=\"intraday-discovery\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern ENABLED_INTRADAY = Pattern.compile(
            "^cdk_scheduler_enabled\\{[^}]*scheduler=\"intraday-discovery\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern ENABLED_NIGHTLY = Pattern.compile(
            "^cdk_scheduler_enabled\\{[^}]*scheduler=\"nightly-discovery\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);

    @Test
    void schedulerMetrics_shouldPublishRunOutcomeAndHeartbeat_afterAScheduledRun() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    final String body = scrape();
                    assertThat(sampleValue(body, RUNS_SUCCESS_INTRADAY))
                            .as("intraday-discovery success counter")
                            .hasValueSatisfying(v -> assertThat(v).isGreaterThanOrEqualTo(1.0));
                    assertThat(sampleValue(body, RUNS_SUCCESS_NIGHTLY))
                            .as("nightly-discovery success counter")
                            .hasValueSatisfying(v -> assertThat(v).isGreaterThanOrEqualTo(1.0));
                });

        final String body = scrape();
        final double nowEpochSeconds = System.currentTimeMillis() / 1000.0;
        assertThat(sampleValue(body, HEARTBEAT_INTRADAY))
                .as("intraday-discovery heartbeat")
                .hasValueSatisfying(v -> {
                    assertThat(v).isGreaterThan(0.0);
                    assertThat(v).isLessThanOrEqualTo(nowEpochSeconds + 5);
                });
    }

    @Test
    void schedulerMetrics_shouldPublishEnabledGaugeForBothSchedulers() {
        // The compose stack sets both CP_CDK_SCHEDULER_*_ENABLED=true, so this proves the
        // enabled-and-present case at the live tier; the disabled/absent-bean case (Scenario 2.1)
        // is unit-tier-only per OQ-014 — this repo's compose stack cannot start a second app
        // container with the flags off.
        final String body = scrape();
        assertThat(sampleValue(body, ENABLED_INTRADAY))
                .as("intraday-discovery enabled gauge")
                .hasValue(1.0);
        assertThat(sampleValue(body, ENABLED_NIGHTLY))
                .as("nightly-discovery enabled gauge")
                .hasValue(1.0);
    }

    private String scrape() {
        final ResponseEntity<String> res = http.getForEntity(baseUrl + "/actuator/prometheus", String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        final String body = res.getBody();
        assertThat(body).as("prometheus scrape body must not be null").isNotNull();
        return body;
    }

    private static Optional<Double> sampleValue(final String body, final Pattern pattern) {
        final Matcher m = pattern.matcher(body);
        if (m.find()) {
            return Optional.of(Double.parseDouble(m.group(1)));
        }
        return Optional.empty();
    }
}
