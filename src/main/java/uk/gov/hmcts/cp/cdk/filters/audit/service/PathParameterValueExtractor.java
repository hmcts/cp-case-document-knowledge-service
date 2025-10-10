package uk.gov.hmcts.cp.cdk.filters.audit.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class PathParameterValueExtractor {

    public Map<String, String> extractPathParameters(final String path, final String regex, final Set<String> parameterNames) {
        final Matcher matcher = Pattern.compile(regex).matcher(path);
        Map<String, String> parameters = new HashMap<>();

        if (matcher.matches()) {
            int index = 1; // group(0) is the entire match, so start from group(1)
            for (String name : parameterNames) {
                parameters.put(name, matcher.group(index++));
            }
        }
        return parameters;
    }
}