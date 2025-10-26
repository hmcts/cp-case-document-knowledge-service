package uk.gov.hmcts.cp.cdk.filters.audit.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.cdk.filters.audit.parser.OpenApiSpecificationParser;
import uk.gov.hmcts.cp.cdk.filters.audit.util.PathParameterNameExtractor;
import uk.gov.hmcts.cp.cdk.filters.audit.util.PathParameterValueExtractor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Service to extract path parameters from servlet paths based on OpenAPI specifications.
 */
@Service
@AllArgsConstructor
public class OpenApiSpecPathParameterService implements PathParameterService {

    private final OpenApiSpecificationParser openApiSpecificationParser;
    private final PathParameterNameExtractor pathParameterNameExtractor;
    private final PathParameterValueExtractor pathParameterValueExtractor;

    @Override
    public Map<String, String> getPathParameters(final String servletPath) {
        final Map<String, Pattern> pathPatterns = openApiSpecificationParser.getPathPatterns();

        final Optional<Map.Entry<String, Pattern>> firstApiEntry = pathPatterns.entrySet().stream()
                .filter(entry -> entry.getValue().matcher(servletPath).matches())
                .findFirst();

        if (firstApiEntry.isPresent()) {
            final Map.Entry<String, Pattern> apiEntry = firstApiEntry.get();
            final List<String> pathParameterNames = pathParameterNameExtractor.extractPathParametersFromApiSpec(apiEntry.getKey());
            return pathParameterValueExtractor.extractPathParameters(servletPath, apiEntry.getValue().pattern(), pathParameterNames);
        }

        return Map.of();
    }
}
