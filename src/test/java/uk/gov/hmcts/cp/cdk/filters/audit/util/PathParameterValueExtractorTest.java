package uk.gov.hmcts.cp.cdk.filters.audit.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.of;

class PathParameterValueExtractorTest {

    private final PathParameterValueExtractor extractor = new PathParameterValueExtractor();

    static Stream<Arguments> pathParameterCases() {
        return Stream.of(
                of("/users/123/orders/456", "/users/(\\d+)/orders/(\\d+)", List.of("userId", "orderId"), Map.of("userId", "123", "orderId", "456")),
                of("/users/123/profile", "/users/(\\d+)/profile", List.of("userId"), Map.of("userId", "123")),
                of("/users/orders", "/users/(\\d+)/orders/(\\d+)", List.of("userId", "orderId"), Map.of()),
                of("", "/users/(\\d+)/orders/(\\d+)", List.of("userId", "orderId"), Map.of()),
                of(null, "/users/(\\d+)/orders/(\\d+)", List.of("userId", "orderId"), Map.of())
        );
    }

    @ParameterizedTest(name = "Extracts path parameters from \"{0}\" using regex \"{1}\"")
    @MethodSource("pathParameterCases")
    @DisplayName("Extracts path parameters from various paths and regex patterns")
    void extractsPathParametersFromVariousPaths(String path, String regex, List<String> parameterNames, Map<String, String> expected) {
        Map<String, String> result = extractor.extractPathParameters(path, regex, parameterNames);
        assertThat(result).containsExactlyInAnyOrderEntriesOf(expected);
    }
}
