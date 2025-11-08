package uk.gov.hmcts.cp.cdk.batch.clients.config;

import uk.gov.hmcts.cp.cdk.batch.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.batch.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.cdk.batch.http.RestClientFactoryConfig.RestClientFactory;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({RagClientProperties.class, CQRSClientProperties.class})
@RequiredArgsConstructor
public class ExternalClientsConfig {

    private final RestClientFactory factory;

    @Bean
    public RestClient ragRestClient(final RagClientProperties props) {
        return factory.build(
                props.getBaseUrl(),
                props.getHeaders(),
                props.connectTimeout(),
                props.readTimeout(),
                false
        );
    }

    @Bean
    public RestClient cqrsRestClient(final RestClientFactory factory, final CQRSClientProperties props) {
        return factory.build(
                props.baseUrl(),
                null,
                props.connectTimeout(),
                props.readTimeout(),
                false
        );
    }
}
