package uk.gov.hmcts.cp.cdk.filters.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.cdk.filters.audit.parser.OpenApiSpecificationParser;
import uk.gov.hmcts.cp.cdk.filters.audit.util.PathParameterNameExtractor;
import uk.gov.hmcts.cp.cdk.filters.audit.util.PathParameterValueExtractor;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenApiSpecPathParameterServiceTest {

    private OpenApiSpecificationParser openApiSpecificationParser;
    private PathParameterNameExtractor pathParameterNameExtractor;
    private PathParameterValueExtractor pathParameterValueExtractor;
    private OpenApiSpecPathParameterService service;

    @BeforeEach
    void setUp() {
        openApiSpecificationParser = mock(OpenApiSpecificationParser.class);
        pathParameterNameExtractor = mock(PathParameterNameExtractor.class);
        pathParameterValueExtractor = mock(PathParameterValueExtractor.class);
        service = new OpenApiSpecPathParameterService(openApiSpecificationParser, pathParameterNameExtractor, pathParameterValueExtractor);
    }

    @Test
    @DisplayName("Returns path parameters when servlet path matches an OpenAPI pattern")
    void returnsPathParametersWhenServletPathMatchesPattern() {
        String servletPath = "/api/resource/123";
        String apiSpecPath = "/api/resource/{id}";
        Pattern pattern = Pattern.compile("/api/resource/\\d+");
        Map<String, Pattern> pathPatterns = Map.of(apiSpecPath, pattern);
        List<String> pathParameterNames = List.of("id");
        Map<String, String> expectedPathParameters = Map.of("id", "123");

        when(openApiSpecificationParser.getPathPatterns()).thenReturn(pathPatterns);
        when(pathParameterNameExtractor.extractPathParametersFromApiSpec(apiSpecPath)).thenReturn(pathParameterNames);
        when(pathParameterValueExtractor.extractPathParameters(servletPath, pattern.pattern(), pathParameterNames))
                .thenReturn(expectedPathParameters);

        Map<String, String> result = service.getPathParameters(servletPath);

        assertThat(result).isEqualTo(expectedPathParameters);
    }

    @Test
    @DisplayName("Returns empty map when servlet path does not match any OpenAPI pattern")
    void returnsEmptyMapWhenServletPathDoesNotMatchPattern() {
        String servletPath = "/api/unknown";
        Map<String, Pattern> pathPatterns = Map.of("/api/resource/{id}", Pattern.compile("/api/resource/\\d+"));

        when(openApiSpecificationParser.getPathPatterns()).thenReturn(pathPatterns);

        Map<String, String> result = service.getPathParameters(servletPath);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty map when OpenAPI patterns are empty")
    void returnsEmptyMapWhenOpenApiPatternsAreEmpty() {
        String servletPath = "/api/resource/123";
        Map<String, Pattern> pathPatterns = Map.of();

        when(openApiSpecificationParser.getPathPatterns()).thenReturn(pathPatterns);

        Map<String, String> result = service.getPathParameters(servletPath);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty map when servlet path is null")
    void returnsEmptyMapWhenServletPathIsNull() {
        Map<String, String> result = service.getPathParameters(null);

        assertThat(result).isEmpty();
    }
}
