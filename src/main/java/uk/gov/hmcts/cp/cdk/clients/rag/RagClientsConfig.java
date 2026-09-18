package uk.gov.hmcts.cp.cdk.clients.rag;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedSynchronouslyApi;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionInitiationApi;
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

    private static final String SUBSCRIPTION_KEY = "subscription-key";
    private static final String RAG_REST_CLIENT = "ragRestClient";

    @Bean
    @ConditionalOnMissingBean(DocumentInformationSummarisedSynchronouslyApi.class)
    public DocumentInformationSummarisedSynchronouslyApi ragAnswerService(
            @Qualifier(RAG_REST_CLIENT) final RestClient ragRestClient,
            final RagClientProperties ragClientProperties,
            final ApimAuthHeaderService apimAuthHeaderService) {

        log.info("Creating DocumentInformationSummarisedSynchronouslyApi client (RAG Answer Service) with auth mode={}",
                ragClientProperties.getAuth() != null ? ragClientProperties.getAuth().getMode() : SUBSCRIPTION_KEY);
        return new RagAnswerServiceImpl(ragRestClient, ragClientProperties, apimAuthHeaderService);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentInformationSummarisedAsynchronouslyApi.class)
    public DocumentInformationSummarisedAsynchronouslyApi ragAnswerServiceAsync(
            @Qualifier(RAG_REST_CLIENT) final RestClient ragRestClient,
            final RagClientProperties ragClientProperties,
            final ApimAuthHeaderService apimAuthHeaderService) {

        log.info("Creating DocumentInformationSummarisedAsynchronouslyApi client (RAG Async Answer Service) with auth mode={}",
                ragClientProperties.getAuth() != null ? ragClientProperties.getAuth().getMode() : SUBSCRIPTION_KEY);

        return new RagAnswerAsyncServiceImpl(ragRestClient, ragClientProperties, apimAuthHeaderService);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentIngestionStatusApi.class)
    public DocumentIngestionStatusApi documentIngestionStatusApi(
            @Qualifier(RAG_REST_CLIENT) final RestClient ragRestClient,
            final RagClientProperties ragClientProperties,
            final ApimAuthHeaderService apimAuthHeaderService) {

        log.info("Creating DocumentIngestionStatusApi client with auth mode={}",
                ragClientProperties.getAuth() != null ? ragClientProperties.getAuth().getMode() : SUBSCRIPTION_KEY);
        return new ApimDocumentIngestionStatusClient(ragRestClient, ragClientProperties, apimAuthHeaderService);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentIngestionInitiationApi.class)
    public DocumentIngestionInitiationApi documentIngestionInitiationApi(
            @Qualifier(RAG_REST_CLIENT) final RestClient ragRestClient,
            final RagClientProperties ragClientProperties,
            final ApimAuthHeaderService apimAuthHeaderService) {

        log.info("Creating DocumentIngestionApi client with auth mode={}",
                ragClientProperties.getAuth() != null ? ragClientProperties.getAuth().getMode() : SUBSCRIPTION_KEY);
        return new ApimDocumentIngestionClient(ragRestClient, ragClientProperties, apimAuthHeaderService);
    }
}
