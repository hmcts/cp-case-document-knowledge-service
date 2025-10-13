package uk.gov.hmcts.cp.cdk.filters.audit.parser;

import java.util.Map;
import java.util.regex.Pattern;

public interface RestApiParser {

    /**
     * Returns a map of API paths to their corresponding regex patterns.
     *
     * @return a map where keys are API paths and values are regex patterns
     */
    Map<String, Pattern> getPathPatterns();
}
