package uk.gov.hmcts.cp.cdk.filters.audit.service;

import uk.gov.hmcts.cp.cdk.filters.audit.util.OpenApiUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class PathParameterService {

    private final OpenApiUtils openApiUtils;
    private final PathParameterNameExtractor pathParameterNameExtractor;
    private final PathParameterValueExtractor pathParameterValueExtractor;

    public Map<String, String> getPathParameters(final String path) {
        final Map<String, Pattern> pathPatterns = openApiUtils.getPathPatterns();

        final Optional<Map.Entry<String, Pattern>> firstApiEntry = pathPatterns.entrySet().stream()
                .filter(entry -> entry.getValue().matcher(path).matches())
                .findFirst();
        if(firstApiEntry.isPresent()) {
            final Map.Entry<String, Pattern> apiEntry = firstApiEntry.get();
            final Set<String> pathParameterNames = pathParameterNameExtractor.extractPathParametersFromPath(apiEntry.getKey());
            return pathParameterValueExtractor.extractPathParameters(path, apiEntry.getValue().pattern(), pathParameterNames);
        };

        return Map.of();


    }
}
