package uk.gov.hmcts.cp.cdk.batch;

import static org.mockito.Mockito.mock;

import uk.gov.hmcts.cp.cdk.batch.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.support.QueryResolver;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;

import com.azure.core.credential.TokenCredential;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@TestConfiguration
public class TestOverrides {

    @Bean
    @Primary
    RetryTemplate retryTemplate() {
        final RetryTemplate retryTemplate = new RetryTemplate();

        final SimpleRetryPolicy simpleRetryPolicy = new SimpleRetryPolicy(1);
        retryTemplate.setRetryPolicy(simpleRetryPolicy);
        final FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(50);
        retryTemplate.setBackOffPolicy(fixedBackOffPolicy);
        return retryTemplate;
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(final EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean(name = "ingestionTaskExecutor")
    @Primary
    public TaskExecutor ingestionTaskExecutor() {
        return new SyncTaskExecutor();
    }

    // External systems as mocks

    @Bean
    @Primary
    public HearingClient hearingClient() {
        return mock(HearingClient.class);
    }

    @Bean
    @Primary
    public ProgressionClient progressionClient() {
        return mock(ProgressionClient.class);
    }

    @Bean
    @Primary
    public DocumentIngestionStatusApi documentIngestionStatusApi() {
        return mock(DocumentIngestionStatusApi.class);
    }

    @Bean
    @Primary
    public DocumentInformationSummarisedApi documentInformationSummarisedApi() {
        return mock(DocumentInformationSummarisedApi.class);
    }

    @Bean
    @Primary
    public QueryResolver queryResolver() {
        return mock(QueryResolver.class);
    }

    @Bean
    @Primary
    public StorageService storageService() {
        return mock(StorageService.class);
    }

    @Bean
    @Primary
    public TokenCredential tokenCredential() {
        return mock(TokenCredential.class);
    }
}
