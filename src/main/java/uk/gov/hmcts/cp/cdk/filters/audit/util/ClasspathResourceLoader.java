package uk.gov.hmcts.cp.cdk.filters.audit.util;

import java.util.Optional;

import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class ClasspathResourceLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClasspathResourceLoader.class);

    private final ResourceLoader resourceLoader;

    public Optional<Resource> loadFilesByPattern(final String resourcePattern) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);

            Resource[] resources = resolver.getResources("classpath*:**/*" + resourcePattern);

            LOGGER.info("Found {} files matching pattern {}", resources.length, resourcePattern);

            return resources.length > 0 ? Optional.of(resources[0]) : Optional.empty();
        } catch (Exception e) {
            LOGGER.error("Error loading resources for pattern: {}", resourcePattern, e);
            return Optional.empty();
        }
    }
}
