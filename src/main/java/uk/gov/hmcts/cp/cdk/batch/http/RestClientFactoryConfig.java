package uk.gov.hmcts.cp.cdk.batch.http;

import java.time.Duration;
import java.util.Map;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientFactoryConfig {

    private static final Duration THREE_MIN = Duration.ofMinutes(3);

    @Bean(destroyMethod = "close")
    public PoolingHttpClientConnectionManager httpClientConnectionManager() {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(200)
                .setMaxConnPerRoute(50)
                .build();
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient closeableHttpClient(final PoolingHttpClientConnectionManager connectionManager) {
        final RequestConfig defaultConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.of(THREE_MIN))
                .setConnectionRequestTimeout(Timeout.of(THREE_MIN))
                .setResponseTimeout(Timeout.of(THREE_MIN))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(defaultConfig)
                .setConnectionManagerShared(true)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofMinutes(3))
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    public RestClientFactory restClientFactory(final CloseableHttpClient httpClient,
                                               final PoolingHttpClientConnectionManager connectionManager) {
        return new RestClientFactory(httpClient, connectionManager);
    }

    public static class RestClientFactory {

        private final CloseableHttpClient baseHttpClient;
        private final PoolingHttpClientConnectionManager connectionManager;

        public RestClientFactory(final CloseableHttpClient baseHttpClient,
                                 final PoolingHttpClientConnectionManager connectionManager) {
            this.baseHttpClient = baseHttpClient;
            this.connectionManager = connectionManager;
        }

        public RestClient build(final String baseUrl,
                                final Map<String, String> defaultHeaders,
                                final Duration connectTimeout,
                                final Duration readTimeout,
                                final boolean enableDebugLogging) {

            final boolean overrideTimeouts = (connectTimeout != null) || (readTimeout != null);

            final CloseableHttpClient clientForThis;
            if (overrideTimeouts) {
                final Duration ct = (connectTimeout != null) ? connectTimeout : THREE_MIN;
                final Duration rt = (readTimeout != null) ? readTimeout : THREE_MIN;

                final RequestConfig perClientConfig = RequestConfig.custom()
                        .setConnectTimeout(Timeout.of(ct))
                        .setConnectionRequestTimeout(Timeout.of(ct))
                        .setResponseTimeout(Timeout.of(rt))
                        .build();

                clientForThis = HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .setConnectionManagerShared(true)
                        .setDefaultRequestConfig(perClientConfig)
                        .evictExpiredConnections()
                        .evictIdleConnections(TimeValue.ofMinutes(3))
                        .disableAutomaticRetries()
                        .build();
            } else {
                clientForThis = this.baseHttpClient;
            }

            final ClientHttpRequestFactory requestFactory =
                    new HttpComponentsClientHttpRequestFactory(clientForThis);

            final RestClient.Builder builder = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(requestFactory)
                    .requestInterceptor(new CorrelationIdInterceptor());

            if (enableDebugLogging) {
                builder.requestInterceptor(new DebugLoggingInterceptor());
            }
            if (defaultHeaders != null && !defaultHeaders.isEmpty()) {
                defaultHeaders.forEach(builder::defaultHeader);
            }
            builder.defaultHeader("Accept-Encoding", "gzip");

            return builder.build();
        }
    }
}
