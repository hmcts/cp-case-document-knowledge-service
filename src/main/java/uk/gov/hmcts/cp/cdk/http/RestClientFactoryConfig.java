package uk.gov.hmcts.cp.cdk.http;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Configuration
public class RestClientFactoryConfig {

    @Bean
    public RestClientFactory restClientFactory() {
        return new RestClientFactory();
    }

    public static class RestClientFactory {

        public RestClient build(String baseUrl,
                                Map<String, String> defaultHeaders,
                                Duration connectTimeout,
                                Duration readTimeout,
                                boolean enableDebugLogging) {

            var cm = PoolingHttpClientConnectionManagerBuilder.create()
                    .setMaxConnTotal(200)
                    .setMaxConnPerRoute(50)
                    .build();

            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(Timeout.of(connectTimeout))
                    .setResponseTimeout(Timeout.of(readTimeout))
                    .build();

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(cm)
                    .setDefaultRequestConfig(requestConfig)
                    .evictExpiredConnections()
                    .evictIdleConnections(TimeValue.ofSeconds(30))
                    .disableAutomaticRetries()
                    .build();

            ClientHttpRequestFactory rf = new HttpComponentsClientHttpRequestFactory(httpClient);

            var builder = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(rf)
                    .requestInterceptor(new CorrelationIdInterceptor());

            if (enableDebugLogging) {
                builder.requestInterceptor(new DebugLoggingInterceptor());
            }

            if (defaultHeaders != null) {
                defaultHeaders.forEach(builder::defaultHeader);
            }
            builder.defaultHeader("Accept-Encoding", "gzip");

            return builder.build();
        }
    }
}
