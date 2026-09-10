package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayName("HttpPoolMetricsConfig tests (DD-43182 Story 4)")
class HttpPoolMetricsConfigTest {

    private static final int MAX_TOTAL = 200;
    private static final int MAX_PER_ROUTE = 50;

    private final HttpPoolMetricsConfig config = new HttpPoolMetricsConfig();
    private final PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder
            .create()
            .setMaxConnTotal(MAX_TOTAL)
            .setMaxConnPerRoute(MAX_PER_ROUTE)
            .build();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeConnectionManager() {
        connectionManager.close();
    }

    private void bindBoth() {
        final PoolingHttpClientConnectionManagerMetricsBinder binder = config.cdkHttpPoolMetrics(connectionManager);
        binder.bindTo(registry);
        final MeterBinder alias = config.cdkHttpPoolLeasedAlias(connectionManager);
        alias.bindTo(registry);
    }

    @Test
    @DisplayName("AC-001: all five binder series are registered")
    void allFivePoolSeriesAreRegistered() {
        bindBoth();

        assertThat(registry.get("httpcomponents.httpclient.pool.total.max").gauge().value()).isEqualTo(MAX_TOTAL);
        // "available" tracks already-established, currently-idle pooled connections — not spare
        // capacity — so a freshly-built manager with no connections ever leased reports 0, not max.
        assertThat(registry.get("httpcomponents.httpclient.pool.total.connections")
                .tag("state", "available").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("httpcomponents.httpclient.pool.total.connections")
                .tag("state", "leased").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("httpcomponents.httpclient.pool.total.pending").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("httpcomponents.httpclient.pool.route.max.default").gauge().value())
                .isEqualTo(MAX_PER_ROUTE);
    }

    @Test
    @DisplayName("AC-003: total max is 200 and per-route max is 50, matching RestClientFactoryConfig")
    void poolMaximaMatchProductionConfiguration() {
        bindBoth();

        assertThat(registry.get("httpcomponents.httpclient.pool.total.max").gauge().value()).isEqualTo(200.0);
        assertThat(registry.get("httpcomponents.httpclient.pool.route.max.default").gauge().value())
                .isEqualTo(50.0);
    }

    @Test
    @DisplayName("AC-002(a): single-snapshot equality at idle between the alias and the binder's own leased series")
    void aliasAgreesWithBinderLeasedSeriesAtIdle() {
        bindBoth();

        final double binderLeased = registry.get("httpcomponents.httpclient.pool.total.connections")
                .tag("state", "leased").gauge().value();
        final double alias = registry.get(CdkMeters.HTTP_POOL_CONNECTIONS_LEASED).gauge().value();

        assertThat(alias).isEqualTo(binderLeased);
        assertThat(alias).isEqualTo(0.0);
    }

    @Test
    @DisplayName("AC-002(b): the alias and the binder's leased series read the identical in-memory struct, "
            + "so they cannot disagree by construction — proven by comparing both against a direct read of "
            + "the same connection manager's own stats at the same instant")
    void aliasAndBinderReadTheIdenticalUnderlyingStruct() {
        bindBoth();

        final double directRead = connectionManager.getTotalStats().getLeased();
        final double binderLeased = registry.get("httpcomponents.httpclient.pool.total.connections")
                .tag("state", "leased").gauge().value();
        final double alias = registry.get(CdkMeters.HTTP_POOL_CONNECTIONS_LEASED).gauge().value();

        assertThat(binderLeased).isEqualTo(directRead);
        assertThat(alias).isEqualTo(directRead);
    }

    @Test
    @DisplayName("AC-005: the alias gauge is a struct read with no I/O or lock — constructing it does not "
            + "touch the network or the database")
    void aliasGaugeIsAPureInMemoryRead() {
        final MeterBinder alias = config.cdkHttpPoolLeasedAlias(connectionManager);

        assertThat(alias).isNotNull();
        alias.bindTo(registry);

        assertThat(registry.get(CdkMeters.HTTP_POOL_CONNECTIONS_LEASED).gauge().value()).isEqualTo(0.0);
    }
}
