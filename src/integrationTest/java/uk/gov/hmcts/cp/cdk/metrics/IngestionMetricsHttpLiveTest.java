package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the ingestion-phase counter and ingestion-duration timer (DD-43182 Stories 1–2) are
 * actually visible, pre-registered, on a live {@code /actuator/prometheus} scrape.
 */
class IngestionMetricsHttpLiveTest extends AbstractHttpLiveTest {

    private static final String[] REACHABLE_PHASES = {
            "WAITING_FOR_UPLOAD", "UPLOADED", "INGESTED", "FAILED", "EXCEEDED_FILE_SIZE_LIMIT"};
    private static final String[] TERMINAL_PHASES = {"INGESTED", "FAILED", "EXCEEDED_FILE_SIZE_LIMIT"};
    private static final String[] SLO_BOUNDARIES_SECONDS = {
            "15.0", "30.0", "60.0", "120.0", "300.0", "600.0", "1800.0", "3600.0"};

    @Test
    void ingestionPhaseCounter_allFivePhaseSeriesForIdpc_arePreRegistered() {
        final String body = scrape();

        for (final String phase : REACHABLE_PHASES) {
            final Pattern pattern = Pattern.compile(
                    "^cdk_document_ingestion_phase_total\\{[^}]*phase=\"" + phase + "\"[^}]*"
                            + "source=\"IDPC\"[^}]*}\\s+\\S+$",
                    Pattern.MULTILINE);
            assertThat(pattern.matcher(body).find())
                    .as("cdk_document_ingestion_phase_total{phase=\"%s\",source=\"IDPC\"} present", phase)
                    .isTrue();
        }
    }

    @Test
    void ingestionDurationTimer_bucketSeries_presentForAllThreeTerminalPhasesWithAllEightSlos() {
        final String body = scrape();

        for (final String phase : TERMINAL_PHASES) {
            final Pattern countPattern = Pattern.compile(
                    "^cdk_document_ingestion_duration_seconds_count\\{[^}]*phase=\"" + phase + "\"[^}]*}\\s+\\S+$",
                    Pattern.MULTILINE);
            assertThat(countPattern.matcher(body).find())
                    .as("cdk_document_ingestion_duration_seconds_count{phase=\"%s\"} present", phase)
                    .isTrue();

            for (final String le : SLO_BOUNDARIES_SECONDS) {
                final Pattern bucketPattern = Pattern.compile(
                        "^cdk_document_ingestion_duration_seconds_bucket\\{[^}]*phase=\"" + phase + "\"[^}]*"
                                + "le=\"" + Pattern.quote(le) + "\"[^}]*}\\s+\\S+$",
                        Pattern.MULTILINE);
                assertThat(bucketPattern.matcher(body).find())
                        .as("cdk_document_ingestion_duration_seconds_bucket{phase=\"%s\",le=\"%s\"} present",
                                phase, le)
                        .isTrue();
            }
        }
    }

    private String scrape() {
        final ResponseEntity<String> res = http.getForEntity(baseUrl + "/actuator/prometheus", String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        final String body = res.getBody();
        assertThat(body).as("prometheus scrape body must not be null").isNotNull();
        return body;
    }
}
