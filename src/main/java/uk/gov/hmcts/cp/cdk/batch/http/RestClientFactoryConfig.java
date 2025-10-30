package uk.gov.hmcts.cp.cdk.batch.http;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Configuration
public class RestClientFactoryConfig {

    @Bean(destroyMethod = "close")
    public PoolingHttpClientConnectionManager httpClientConnectionManager() {
        final PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(200)
                        .setMaxConnPerRoute(50)
                        .build();
        return connectionManager;
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient closeableHttpClient(final PoolingHttpClientConnectionManager connectionManager) {
        final CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .disableAutomaticRetries()
                .build();
        return httpClient;
    }

    @Bean
    public RestClientFactory restClientFactory(final CloseableHttpClient httpClient) {
        return new RestClientFactory(httpClient);
    }

    public static class RestClientFactory {

        private final CloseableHttpClient httpClient;

        public RestClientFactory(final CloseableHttpClient httpClient) {
            this.httpClient = httpClient;
        }

        public RestClient build(final String baseUrl,
                                final Map<String, String> defaultHeaders,
                                final Duration connectTimeout,
                                final Duration readTimeout,
                                final boolean enableDebugLogging) {

            final HttpComponentsClientHttpRequestFactory requestFactory =
                    new HttpComponentsClientHttpRequestFactory(this.httpClient);
            // Per-client timeouts
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);

            final ClientHttpRequestFactory clientRequestFactory = requestFactory;

            final RestClient.Builder builder = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(clientRequestFactory)
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
