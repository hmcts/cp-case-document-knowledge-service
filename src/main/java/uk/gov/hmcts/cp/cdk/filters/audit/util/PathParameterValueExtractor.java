package uk.gov.hmcts.cp.cdk.filters.audit.util;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static java.util.regex.Pattern.compile;

@Service
public class PathParameterValueExtractor {

    public Map<String, String> extractPathParameters(String path, String regex, List<String> parameterNames) {
        if (path == null) {
            return Map.of();
        }

        Matcher matcher = compile(regex).matcher(path);
        if (!matcher.matches()) {
            return Map.of();
        }

        Map<String, String> parameters = new HashMap<>();
        int index = 1;
        for (String name : parameterNames) {
            parameters.put(name, matcher.group(index++));
        }
        return parameters;
    }
}
