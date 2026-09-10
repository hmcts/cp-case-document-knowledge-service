package uk.gov.hmcts.cp.cdk.metrics;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;

/**
 * Publishes the shared Apache HttpClient connection pool's leased, available, pending and maximum
 * connection counts on {@code /actuator/prometheus} (DD-43182 Story 4, ADR-008).
 *
 * <p>Covers all outbound Apache-HttpClient traffic (RAG, Progression, Hearing) through the single
 * shared {@code RestClientFactoryConfig.httpClientConnectionManager()} bean. {@code
 * dependency="azure_blob"} is <strong>not</strong> covered — {@code AzureBlobStorageService} uses
 * the Azure SDK's own HTTP stack, not this pool.
 *
 * <p>{@link CdkMeters#HTTP_POOL_CONNECTIONS_LEASED} is a thin alias of the framework binder's own
 * {@code httpcomponents_httpclient_pool_total_connections{state="leased"}} series, reading the same
 * in-memory {@code ConnPoolControl.getTotalStats()} struct so the two names can never disagree
 * (GATE-4) — kept because an existing alert convention names {@code cdk_http_pool_connections_leased}
 * specifically, and renaming a metric once alert rules exist elsewhere is a coordinated
 * cross-repository change.
 *
 * <p>Alert on the ratio of leased to max, not on the alias alone — the alias cannot express
 * exhaustion by itself:
 * <pre>{@code
 * max by (service, cluster) (httpcomponents_httpclient_pool_total_connections{state="leased"})
 *   / on (service, cluster)
 * max by (service, cluster) (httpcomponents_httpclient_pool_total_max) > 0.8
 * }</pre>
 */
@Configuration
public class HttpPoolMetricsConfig {

    private static final String POOL_NAME = "cdk";

    @Bean
    public PoolingHttpClientConnectionManagerMetricsBinder cdkHttpPoolMetrics(
            final PoolingHttpClientConnectionManager connectionManager) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, POOL_NAME);
    }

    @Bean
    public MeterBinder cdkHttpPoolLeasedAlias(final PoolingHttpClientConnectionManager connectionManager) {
        return registry -> Gauge.builder(CdkMeters.HTTP_POOL_CONNECTIONS_LEASED,
                        connectionManager, cm -> cm.getTotalStats().getLeased())
                .description("Leased connections on the shared Apache HttpClient pool "
                        + "(alias of httpcomponents_httpclient_pool_total_connections{state=\"leased\"})")
                .strongReference(true)
                .register(registry);
    }
}
