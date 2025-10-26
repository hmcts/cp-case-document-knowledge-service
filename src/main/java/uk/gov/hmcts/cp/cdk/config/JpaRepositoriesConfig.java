package uk.gov.hmcts.cp.cdk.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import uk.gov.hmcts.cp.cdk.repo.*;

/**
 * Restrict Spring Data to only the real JPA repository interfaces.
 * Prevents it from trying to treat the imperative/native {@code QueriesAsOfRepository}
 * as a Spring Data repository (and from treating any inner "Row" projection as an entity).
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = {
        AnswerRepository.class,
        IngestionStatusViewRepository.class,
        QueriesAsOfRepository.class,
        QueryRepository.class,
        QueryVersionRepository.class
})
public class JpaRepositoriesConfig {
}
