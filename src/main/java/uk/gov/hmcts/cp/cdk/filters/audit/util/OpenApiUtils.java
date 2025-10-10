package uk.gov.hmcts.cp.cdk.filters.audit.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Getter
@Component
public class OpenApiUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiUtils.class);

    private final Map<String, Pattern> pathPatterns = new HashMap<>();

    @PostConstruct
    public void init() {
        OpenAPI openAPI = new OpenAPIParser().readLocation("classpath:case-admin-doc-knowledge-api.openapi.yml", null, null).getOpenAPI();

        if (openAPI == null || openAPI.getPaths() == null || openAPI.getPaths().isEmpty()) {
            // Perhaps fail the build
            return;
        }

        LOGGER.info("Loaded {} paths from OpenAPI specification", openAPI.getPaths().size());

        openAPI.getPaths().forEach((path, pathItem) -> {
            boolean hasPathParams = pathItem.getParameters() != null && pathItem.getParameters().stream()
                    .anyMatch(param -> "path".equalsIgnoreCase(param.getIn()));

            if (hasPathParams) {
                String regexPath = path.replaceAll("\\{[^/]+}", "([^/]+)");
                pathPatterns.put(path, Pattern.compile(regexPath));
            }
        });
    }

}