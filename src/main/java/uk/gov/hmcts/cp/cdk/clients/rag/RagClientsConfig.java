package uk.gov.hmcts.cp.cdk.clients.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;


@Configuration
@EnableConfigurationProperties({RagClientProperties.class})
public class RagClientsConfig {

    @Bean
    public DocumentInformationSummarisedApi ragAnswerService(@Qualifier("ragRestClient") RestClient ragRestClient, RagClientProperties props) {
        return new RagAnswerServiceImpl(ragRestClient, props);
    }
    @Bean
    public DocumentIngestionStatusApi documentIngestionStatusApi(
            @Qualifier("ragRestClient") RestClient restClient, RagClientProperties props) {
        return new ApimDocumentIngestionStatusClient(restClient, props);
    }
}
