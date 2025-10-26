package uk.gov.hmcts.cp.cdk.repo;


import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Include all @Repository classes in uk.gov.hmcts.cp.cdk.repo for the JPA slice.
 */
@Configuration
@ComponentScan(
        basePackages = "uk.gov.hmcts.cp.cdk.repo",
        includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = org.springframework.stereotype.Repository.class)
)
public class TestJpaConfig {
}
