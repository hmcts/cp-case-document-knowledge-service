package uk.gov.hmcts.cp.cdk.filters.audit.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Getter
@Component
public class OpenApiParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiParser.class);

    private final Map<String, Pattern> pathPatterns = new HashMap<>();

    private final ResourceLoader resourceLoader;

    @Value("${cp.audit.rest-spec}")
    private String restSpecification;

    public OpenApiParser(final ResourceLoader resourceLoader, @Value("${cp.audit.rest-spec}") final String restSpecification) {
        this.resourceLoader = resourceLoader;
        this.restSpecification = restSpecification;
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
            openAPI = new OpenAPIParser().readLocation(specificationUrl, null, null).getOpenAPI();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to parse OpenAPI specification at location", e);
        }

        if (null == openAPI || null == openAPI.getPaths() || openAPI.getPaths().isEmpty()) {
            // Perhaps fail the build
            LOGGER.warn("Supplied specification has no endpoints defined: {}", restSpecification);
            throw new IllegalArgumentException("Supplied specification has no endpoints defined: " + restSpecification);
        }

        LOGGER.info("Loaded {} paths from OpenAPI specification", ()-> openAPI.getPaths().size());

        openAPI.getPaths().forEach((path, pathItem) -> {
            final boolean hasPathParams = pathItem.getParameters() != null && pathItem.getParameters().stream()
                    .anyMatch(param -> "path".equalsIgnoreCase(param.getIn()));

            if (hasPathParams) {
                final String regexPath = path.replaceAll("\\{[^/]+}", "([^/]+)");
                pathPatterns.put(path, Pattern.compile(regexPath));
            }
        });
    }

}