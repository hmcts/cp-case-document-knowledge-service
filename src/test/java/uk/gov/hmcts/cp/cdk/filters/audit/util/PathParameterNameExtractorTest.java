package uk.gov.hmcts.cp.cdk.filters.audit.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.of;

class PathParameterNameExtractorTest {

    private final PathParameterNameExtractor extractor = new PathParameterNameExtractor();

    static Stream<Arguments> pathParameterCases() {
        return Stream.of(
                of("/users/{userId}/orders/{orderId}", List.of("userId", "orderId")),
                of("/users/orders", List.of()),
                of("/users/{userId}/profile", List.of("userId")),
                of("", List.of()),
                of(null, List.of())
        );
    }

    @ParameterizedTest(name = "Extracts path parameters from \"{0}\"")
    @MethodSource("pathParameterCases")
    @DisplayName("Extracts path parameters from various API specs")
    void extractsPathParametersFromVariousApiSpecs(String path, List<String> expected) {
        List<String> result = extractor.extractPathParametersFromApiSpec(path);
        assertThat(result).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("Ignores invalid path parameter syntax")
    void ignoresInvalidPathParameterSyntax() {
        String path = "/users/{userId/orders/{orderId}";
        List<String> result = extractor.extractPathParametersFromApiSpec(path);
        assertThat(result).containsExactly("orderId");
    }
}
