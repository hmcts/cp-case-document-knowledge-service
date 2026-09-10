package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * DD-43182 Story 7, AC-003/AC-004: merge-blocking whole-endpoint series-count ceiling and a CI
 * smoke-bound scrape-time check (GATE-6 re-scope). Neither assertion is a production guarantee —
 * see each test's own failure message for why.
 */
class PrometheusSeriesBudgetHttpLiveTest {

    /**
     * Compose exercises far fewer distinct {@code http_server_requests_seconds} URI/status/method/
     * outcome combinations than production, so the compose series count is a <em>lower</em> bound on
     * production's — this ceiling is deliberately tighter than the 2,000-series production budget
     * (NFR-002), not a claim that production stays under it too. See {@code baseline-series-count.md}
     * for the measured pre-DD-43182 figure (160) this ceiling is set against, and {@code 02-design.md}
     * §12 for the full worst-case arithmetic (246 series once DD-43182 and DD-43185 are both fully
     * warmed up in production).
     */
    private static final int COMPOSE_SERIES_CEILING = 1200;

    /**
     * A hard sub-second bound on shared CI hardware is a flaky test, not a guarantee (GATE-6, the
     * same reasoning DD-43185 §12 applied to its own EXPLAIN bound). This is a CI smoke bound only —
     * a one-off production scrape timing is captured separately in {@code deploy-notes.md}.
     */
    private static final long CI_SMOKE_SCRAPE_TIME_BOUND_MILLIS = 2000L;

    private static final Pattern COMMENT_OR_BLANK_LINE = Pattern.compile("^(#.*)?$");

    private final String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8082/casedocumentknowledge-service");
    private final RestTemplate http = new RestTemplate();

    @Test
    void wholeEndpointSeriesCountStaysUnderTheComposeCeiling() {
        final String body = scrapePrometheus();

        final long seriesCount = body.lines().filter(line -> !COMMENT_OR_BLANK_LINE.matcher(line).matches()).count();

        assertThat(seriesCount)
                .as("whole-endpoint /actuator/prometheus series count must stay under %d in the compose "
                        + "stack (NFR-002) — this is a lower bound on production, which exercises more "
                        + "distinct http_server_requests_seconds tag combinations than this suite does; "
                        + "see baseline-series-count.md and 02-design.md §12 for the full accounting",
                        COMPOSE_SERIES_CEILING)
                .isLessThan(COMPOSE_SERIES_CEILING);
    }

    @Test
    void scrapeCompletesWithinTheCiSmokeBound() {
        final long startNanos = System.nanoTime();
        scrapePrometheus();
        final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMillis)
                .as("CI smoke bound only (GATE-6) — not a production performance guarantee; a hard "
                        + "sub-second assertion on shared CI hardware is a flaky test, not a guarantee. "
                        + "A one-off production scrape timing is captured separately in deploy-notes.md")
                .isLessThan(CI_SMOKE_SCRAPE_TIME_BOUND_MILLIS);
    }

    private String scrapePrometheus() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN));
        final ResponseEntity<String> response = http.exchange(
                baseUrl + "/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
