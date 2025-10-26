package uk.gov.hmcts.cp.cdk.filters.audit.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ClasspathResourceLoaderTest.TestConfig.class)
class ClasspathResourceLoaderTest {

    // Inject the component we are testing
    @Autowired
    private ClasspathResourceLoader resourceLoader;

    @Test
    void loadFilesByPattern_shouldFindFileSuccessfully() {
        final String pattern = "-res.txt";

        // Act
        Optional<Resource> result = resourceLoader.loadFilesByPattern(pattern);

        // Assert
        assertTrue(result.isPresent(), "Resource should be found on the classpath.");

        // Verify the file name ends correctly
        assertTrue(result.get().getFilename().equalsIgnoreCase("test-res.txt"), "The found resource should be the expected file.");
    }

    @Test
    void loadFilesByPattern_shouldFindNestedFileSuccessfully() {
        final String pattern = "-test-*.txt";

        // Act
        Optional<Resource> result = resourceLoader.loadFilesByPattern(pattern);

        // Assert
        assertTrue(result.isPresent(), "Resource should be found on the classpath.");

        // Verify the file name ends correctly
        assertTrue(result.get().getFilename().equalsIgnoreCase("nested-test-resource.txt"), "The found resource should be the expected file.");
    }

    @Test
    void loadFilesByFullName_shouldFindFileSuccessfully() {
        final String pattern = "nested-test-resource.txt";

        // Act
        Optional<Resource> result = resourceLoader.loadFilesByPattern(pattern);

        // Assert
        assertTrue(result.isPresent(), "Resource should be found on the classpath.");

        // Verify the file name ends correctly
        assertTrue(result.get().getFilename().equalsIgnoreCase("nested-test-resource.txt"), "The found resource should be the expected file.");
    }

    /**
     * Test case 2: Fails gracefully when no file matches the pattern.
     */
    @Test
    void loadFilesByPattern_shouldReturnEmptyWhenNoMatch() {
        // Arrange
        // A pattern that should never match any file
        final String pattern = "nonexistent-file-123.yaml";

        // Act
        Optional<Resource> result = resourceLoader.loadFilesByPattern(pattern);

        // Assert
        assertTrue(result.isEmpty(), "Result should be empty when no resource matches the pattern.");
    }

    @Configuration
    static class TestConfig {
        @Bean
        public ClasspathResourceLoader classpathResourceLoader(ResourceLoader resourceLoader) {
            return new ClasspathResourceLoader(resourceLoader);
        }
    }
}