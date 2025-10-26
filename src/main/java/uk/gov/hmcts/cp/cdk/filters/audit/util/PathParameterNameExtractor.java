package uk.gov.hmcts.cp.cdk.filters.audit.util;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

@Service
public class PathParameterNameExtractor {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(\\w+)}");

    public List<String> extractPathParametersFromApiSpec(final String path) {

        if (null == path) {
            return List.of();
        }

        return stream(path.split("/"))
                .filter(pathSegment -> PATH_PARAM_PATTERN.matcher(pathSegment).matches())
                .map(pathSegment -> getPathVariable(PATH_PARAM_PATTERN.matcher(pathSegment)))
                .collect(toList());
    }

    private String getPathVariable(final Matcher matcher) {
        return matcher.find() ? matcher.group(0).substring(1, matcher.end() - 1) : null;
    }
}
