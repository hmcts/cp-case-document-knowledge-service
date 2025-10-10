package uk.gov.hmcts.cp.cdk.filters.audit.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class PathMatcher {

    public Map<String, String> extractPathParameters(final String path, final String regex, final List<String> parameterNames) {
        final Pattern pattern = Pattern.compile(regex);
        Map<String, String> parameters = new HashMap<>();
        Matcher matcher = pattern.matcher(path);

        if (matcher.matches()) {
            for (int i = 0; i < parameterNames.size(); i++) {
                String name = parameterNames.get(i);
                String value = matcher.group(i + 1); // group(0) is the entire match
                parameters.put(name, value);
            }
        }
        return parameters;
    }
}