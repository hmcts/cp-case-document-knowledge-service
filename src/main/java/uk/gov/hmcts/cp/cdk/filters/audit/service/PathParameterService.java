package uk.gov.hmcts.cp.cdk.filters.audit.service;

import uk.gov.hmcts.cp.cdk.filters.audit.util.OpenApiParser;
import uk.gov.hmcts.cp.cdk.filters.audit.util.PathParameterNameExtractor;
import uk.gov.hmcts.cp.cdk.filters.audit.util.PathParameterValueExtractor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class PathParameterService {

    private final OpenApiParser openApiParser;
    private final PathParameterNameExtractor pathParameterNameExtractor;
    private final PathParameterValueExtractor pathParameterValueExtractor;

    public Map<String, String> getPathParameters(final String servletPath) {
        final Map<String, Pattern> pathPatterns = openApiParser.getPathPatterns();

        final Optional<Map.Entry<String, Pattern>> firstApiEntry = pathPatterns.entrySet().stream()
                .filter(entry -> entry.getValue().matcher(servletPath).matches())
                .findFirst();

        if (firstApiEntry.isPresent()) {
            final Map.Entry<String, Pattern> apiEntry = firstApiEntry.get();
            final List<String> pathParameterNames = pathParameterNameExtractor.extractPathParametersFromApiSpec(apiEntry.getKey());
            return pathParameterValueExtractor.extractPathParameters(servletPath, apiEntry.getValue().pattern(), pathParameterNames);
        }
        ;

        return Map.of();


    }
}
