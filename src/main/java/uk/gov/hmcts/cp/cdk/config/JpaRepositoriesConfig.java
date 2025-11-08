package uk.gov.hmcts.cp.cdk.config;

import uk.gov.hmcts.cp.cdk.repo.AnswerRepository;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.CaseQueryStatusRepository;
import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.cdk.repo.QueriesAsOfRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryVersionRepository;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Restrict Spring Data to only the real JPA repository interfaces.
 * Prevents it from trying to treat the imperative/native {@code QueriesAsOfRepository}
 * as a Spring Data repository (and from treating any inner "Row" projection as an entity).
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = {
        AnswerRepository.class,
        CaseDocumentRepository.class,
        CaseQueryStatusRepository.class,
        IngestionStatusViewRepository.class,
        QueriesAsOfRepository.class,
        QueryDefinitionLatestRepository.class,
        QueryRepository.class,
        QueryVersionRepository.class
})
public class JpaRepositoriesConfig {
}
