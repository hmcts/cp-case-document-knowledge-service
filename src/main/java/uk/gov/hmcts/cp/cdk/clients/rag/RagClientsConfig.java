package uk.gov.hmcts.cp.cdk.clients.rag;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
@EnableConfigurationProperties({RagClientProperties.class})
public class RagClientsConfig {

    @Bean
    @ConditionalOnMissingBean(DocumentInformationSummarisedApi.class)
    public DocumentInformationSummarisedApi ragAnswerService(
            @Qualifier("ragRestClient") final RestClient ragRestClient,
            final RagClientProperties ragClientProperties,
            final ApimAuthHeaderService apimAuthHeaderService) {

        log.info("Creating DocumentInformationSummarisedApi client (RAG Answer Service) with auth mode={}",
                ragClientProperties.getAuth() != null ? ragClientProperties.getAuth().getMode() : "subscription-key");
        return new RagAnswerServiceImpl(ragRestClient, ragClientProperties, apimAuthHeaderService);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentIngestionStatusApi.class)
    public DocumentIngestionStatusApi documentIngestionStatusApi(
            @Qualifier("ragRestClient") final RestClient ragRestClient,
            final RagClientProperties ragClientProperties,
            final ApimAuthHeaderService apimAuthHeaderService) {

        log.info("Creating DocumentIngestionStatusApi client with auth mode={}",
                ragClientProperties.getAuth() != null ? ragClientProperties.getAuth().getMode() : "subscription-key");
        return new ApimDocumentIngestionStatusClient(ragRestClient, ragClientProperties, apimAuthHeaderService);
    }
}
