package uk.gov.hmcts.cp.cdk.clients.config;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.cdk.http.RestClientFactoryConfig.RestClientFactory;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({RagClientProperties.class, CQRSClientProperties.class})
@RequiredArgsConstructor
public class ExternalClientsConfig {

    private final RestClientFactory restClientFactory;

    @Bean
    public RestClient ragRestClient(final RagClientProperties properties) {
        return restClientFactory.build(
                properties.getBaseUrl(),
                properties.getHeaders(),
                properties.connectTimeout(),
                properties.readTimeout(),
                false
        );
    }

    @Bean
    public RestClient cqrsRestClient(final RestClientFactory factory, final CQRSClientProperties properties) {
        return factory.build(
                properties.baseUrl(),
                null,
                properties.connectTimeout(),
                properties.readTimeout(),
                false
        );
    }
}
