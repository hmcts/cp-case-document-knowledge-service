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
        final RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.of(THREE_MIN))
                .setConnectionRequestTimeout(Timeout.of(THREE_MIN))
                .setResponseTimeout(Timeout.of(THREE_MIN))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofMinutes(3))
                .disableAutomaticRetries()
                .build();
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
            requestFactory.setConnectTimeout(connectTimeout != null ? connectTimeout : THREE_MIN);
            requestFactory.setReadTimeout(readTimeout != null ? readTimeout : THREE_MIN);
            requestFactory.setConnectionRequestTimeout(THREE_MIN);

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
