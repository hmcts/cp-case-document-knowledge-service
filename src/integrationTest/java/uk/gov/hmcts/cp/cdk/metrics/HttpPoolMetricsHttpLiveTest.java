package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the HTTP connection-pool visibility series (DD-43182 Story 4, ADR-008) are actually
 * observable on a live {@code /actuator/prometheus} scrape.
 */
class HttpPoolMetricsHttpLiveTest extends AbstractHttpLiveTest {

    private static final Pattern POOL_TOTAL_MAX = Pattern.compile(
            "^httpcomponents_httpclient_pool_total_max\\{[^}]*httpclient=\"cdk\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern POOL_AVAILABLE = Pattern.compile(
            "^httpcomponents_httpclient_pool_total_connections\\{[^}]*httpclient=\"cdk\"[^}]*"
                    + "state=\"available\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern POOL_LEASED = Pattern.compile(
            "^httpcomponents_httpclient_pool_total_connections\\{[^}]*httpclient=\"cdk\"[^}]*"
                    + "state=\"leased\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern POOL_PENDING = Pattern.compile(
            "^httpcomponents_httpclient_pool_total_pending\\{[^}]*httpclient=\"cdk\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern POOL_ROUTE_MAX = Pattern.compile(
            "^httpcomponents_httpclient_pool_route_max_default\\{[^}]*httpclient=\"cdk\"[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);
    private static final Pattern POOL_LEASED_ALIAS = Pattern.compile(
            "^cdk_http_pool_connections_leased\\{[^}]*}\\s+(\\S+)$",
            Pattern.MULTILINE);

    @Test
    void httpPoolMetrics_shouldPublishAllFiveBinderSeriesAndTheLeasedAlias() {
        final String body = scrape();

        assertThat(sampleValue(body, POOL_TOTAL_MAX)).as("pool total max").hasValue(200.0);
        assertThat(sampleValue(body, POOL_ROUTE_MAX)).as("pool route max default").hasValue(50.0);
        assertThat(sampleValue(body, POOL_AVAILABLE)).as("pool available").isPresent();
        assertThat(sampleValue(body, POOL_LEASED)).as("pool leased").isPresent();
        assertThat(sampleValue(body, POOL_PENDING)).as("pool pending").isPresent();
    }

    @Test
    void httpPoolMetrics_leasedAlias_shouldAgreeWithBinderOwnLeasedSeries_atASingleSnapshot() {
        final String body = scrape();

        final Optional<Double> binderLeased = sampleValue(body, POOL_LEASED);
        final Optional<Double> alias = sampleValue(body, POOL_LEASED_ALIAS);

        assertThat(binderLeased).as("binder's own leased series").isPresent();
        assertThat(alias).as("cdk_http_pool_connections_leased alias").isPresent();
        assertThat(alias).isEqualTo(binderLeased);
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
