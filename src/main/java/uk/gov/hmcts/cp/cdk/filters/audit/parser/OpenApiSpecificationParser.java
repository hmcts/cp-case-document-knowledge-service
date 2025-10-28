package uk.gov.hmcts.cp.cdk.filters.audit.parser;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.filters.audit.util.ClasspathResourceLoader;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Getter
@Component
public class OpenApiSpecificationParser implements RestApiParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiSpecificationParser.class);

    private final Map<String, Pattern> pathPatterns = new HashMap<>();

    private final ClasspathResourceLoader resourceLoader;

    private final OpenAPIParser openAPIParser;

    @Value("${cp.audit.rest-spec}")
    private final String restSpecification;

    public OpenApiSpecificationParser(final ClasspathResourceLoader resourceLoader, @Value("${cp.audit.rest-spec}") final String restSpecification, final OpenAPIParser openAPIParser) {
        this.resourceLoader = resourceLoader;
        this.restSpecification = restSpecification;
        this.openAPIParser = openAPIParser;
    }

    @PostConstruct
    public void init() {

        final Optional<Resource> optionalResource = resourceLoader.loadFilesByPattern(restSpecification);

        if (optionalResource.isEmpty()) {
            LOGGER.warn("No OpenAPI specification found at the specified path: {}", restSpecification);
            throw new IllegalArgumentException("No OpenAPI specification found at the specified path");
        }

        OpenAPI openAPI;
        try {
            final String specificationUrl = optionalResource.get().getURL().toString();
            openAPI = openAPIParser.readLocation(specificationUrl, null, null).getOpenAPI();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to parse OpenAPI specification at location", e);
        }

        final Paths paths = openAPI.getPaths();
        if (null == paths || paths.isEmpty()) {
            // Perhaps fail the build
            LOGGER.warn("Supplied specification has no endpoints defined: {}", restSpecification);
            throw new IllegalArgumentException("Supplied specification has no endpoints defined: " + restSpecification);
        }

        LOGGER.info("Loaded {} paths from OpenAPI specification", paths.size());

        paths.forEach((path, pathItem) -> {
            if (null == pathItem || null == path) {
                throw new IllegalArgumentException("Invalid path specifications in file : " + restSpecification);
            }
            final boolean hasPathParams = pathItem.getParameters() != null && pathItem.getParameters().stream()
                    .anyMatch(param -> "path".equalsIgnoreCase(param.getIn()));

            if (hasPathParams) {
                final String regexPath = path.replaceAll("\\{[^/]+}", "([^/]+)");
                pathPatterns.put(path, Pattern.compile(regexPath));
            }
        });
    }

}