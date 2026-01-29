package uk.gov.hmcts.cp.cdk.config;

import uk.gov.hmcts.cp.cdk.domain.CdkEntityMarker;
import uk.gov.hmcts.cp.cdk.repo.CdkPersistenceMarker;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

@Configuration
@Import(CdkJpaAutoConfiguration.PersistencePackagesRegistrar.class)
public class CdkJpaAutoConfiguration {

    static final class PersistencePackagesRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            // for general base-package defaults (repositories, etc.)
            AutoConfigurationPackages.register(registry, CdkPersistenceMarker.class.getPackageName());
            // for JPA entity scanning
            EntityScanPackages.register(registry, CdkEntityMarker.class.getPackageName());
        }
    }
}
