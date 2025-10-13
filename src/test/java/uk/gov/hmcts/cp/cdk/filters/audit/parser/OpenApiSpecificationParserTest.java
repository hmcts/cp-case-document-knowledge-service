package uk.gov.hmcts.cp.cdk.filters.audit.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.filters.audit.util.ClasspathResourceLoader;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

class OpenApiSpecificationParserTest {

    @Test
    @DisplayName("Throws exception when OpenAPI specification path is null")
    void throwsExceptionWhenOpenApiSpecPathIsNull() {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, null, openAPIParser);

        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No OpenAPI specification found at the specified path");
    }

    @Test
    @DisplayName("Throws exception when OpenAPI specification resource is missing")
    void throwsExceptionWhenOpenApiSpecResourceIsMissing() {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.empty());
        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "classpath:/openapi.yaml", openAPIParser);

        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No OpenAPI specification found at the specified path");
    }

    @Test
    @DisplayName("Throws exception when OpenAPI specification cannot be read")
    void throwsExceptionWhenOpenApiSpecCannotBeRead() throws Exception {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenThrow(new IOException("IO error"));
        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "classpath:/openapi.yaml", openAPIParser);

        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to parse OpenAPI specification at location");
    }

    @Test
    @DisplayName("Throws exception when OpenAPI specification has no paths")
    void throwsExceptionWhenOpenApiSpecHasNoPaths() throws Exception {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL("file:/dummy/path"));

        OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(null);

        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "classpath:/openapi.yaml", openAPIParser);

        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supplied specification has no endpoints defined");
    }

    @Test
    @DisplayName("Adds path patterns for multiple valid paths")
    void addsPathPatternsForMultipleValidPaths() throws Exception {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL("file:/dummy/path"));

        Parameter pathParam = new Parameter().in("path").name("id");
        PathItem pathItem1 = new PathItem().parameters(List.of(pathParam));
        PathItem pathItem2 = new PathItem().parameters(List.of(pathParam));
        Paths paths = new Paths();
        paths.addPathItem("/api/resource/{id}", pathItem1);
        paths.addPathItem("/api/other-resource/{id}", pathItem2);

        OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "classpath:/openapi.yaml", openAPIParser);
        parser.init();

        Map<String, Pattern> patterns = parser.getPathPatterns();
        assertThat(patterns).containsKey("/api/resource/{id}");
        assertThat(patterns).containsKey("/api/other-resource/{id}");
        assertThat(patterns.get("/api/resource/{id}").pattern()).isEqualTo("/api/resource/([^/]+)");
        assertThat(patterns.get("/api/other-resource/{id}").pattern()).isEqualTo("/api/other-resource/([^/]+)");
    }

    @Test
    @DisplayName("Does not add path pattern if path has no path parameters")
    void doesNotAddPathPatternIfNoPathParameters() throws Exception {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL("file:/dummy/path"));

        Parameter queryParam = new Parameter().in("query").name("q");
        PathItem pathItem = new PathItem().parameters(List.of(queryParam));
        Paths paths = new Paths();
        paths.addPathItem("/api/resource", pathItem);

        OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "classpath:/openapi.yaml", openAPIParser);
        parser.init();

        assertThat(parser.getPathPatterns()).isEmpty();
    }

    @Test
    @DisplayName("Does not add path pattern if path parameters are null")
    void doesNotAddPathPatternIfPathParametersAreNull() throws Exception {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL("file:/dummy/path"));

        PathItem pathItem = new PathItem().parameters(null);
        Paths paths = new Paths();
        paths.addPathItem("/api/resource/{id}", pathItem);

        OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "classpath:/openapi.yaml", openAPIParser);
        parser.init();

        assertThat(parser.getPathPatterns()).isEmpty();
    }

    @Test
    @DisplayName("Adds multiple path patterns for paths with multiple path parameters")
    void addsMultiplePathPatternsForPathsWithMultiplePathParameters() throws Exception {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL("file:/dummy/path"));

        Parameter pathParam1 = new Parameter().in("path").name("id");
        Parameter pathParam2 = new Parameter().in("path").name("subId");
        PathItem pathItem = new PathItem().parameters(List.of(pathParam1, pathParam2));
        Paths paths = new Paths();
        paths.addPathItem("/api/resource/{id}/sub-resource/{subId}", pathItem);

        OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "classpath:/openapi.yaml", openAPIParser);
        parser.init();

        Map<String, Pattern> patterns = parser.getPathPatterns();
        assertThat(patterns).containsKey("/api/resource/{id}/sub-resource/{subId}");
        assertThat(patterns.get("/api/resource/{id}/sub-resource/{subId}").pattern()).isEqualTo("/api/resource/([^/]+)/sub-resource/([^/]+)");
    }

    @Test
    @DisplayName("Throws exception if OpenAPI specification contains invalid paths")
    void doesNotAddPathPatternIfOpenApiSpecContainsInvalidPaths() throws Exception {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL("file:/dummy/path"));

        Paths paths = new Paths();
        paths.addPathItem("/api/resource/{id}", null);

        OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "openapi.yaml", openAPIParser);
        assertThatThrownBy(parser::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid path specifications in file : " + "openapi.yaml");
    }

    @Test
    @DisplayName("Adds path pattern for paths with mixed parameter types")
    void addsPathPatternForPathsWithMixedParameterTypes() throws Exception {
        ClasspathResourceLoader resourceLoader = mock(ClasspathResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.loadFilesByPattern(anyString())).thenReturn(Optional.of(resource));
        when(resource.getURL()).thenReturn(new URL("file:/dummy/path"));

        Parameter pathParam = new Parameter().in("path").name("id");
        Parameter queryParam = new Parameter().in("query").name("q");
        PathItem pathItem = new PathItem().parameters(List.of(pathParam, queryParam));
        Paths paths = new Paths();
        paths.addPathItem("/api/resource/{id}", pathItem);

        OpenAPI openAPI = mock(OpenAPI.class);
        when(openAPI.getPaths()).thenReturn(paths);

        OpenAPIParser openAPIParser = mock(OpenAPIParser.class);
        SwaggerParseResult result = new SwaggerParseResult();
        result.setOpenAPI(openAPI);
        when(openAPIParser.readLocation(anyString(), isNull(), isNull())).thenReturn(result);

        OpenApiSpecificationParser parser = new OpenApiSpecificationParser(resourceLoader, "classpath:/openapi.yaml", openAPIParser);
        parser.init();

        Map<String, Pattern> patterns = parser.getPathPatterns();
        assertThat(patterns).containsKey("/api/resource/{id}");
        assertThat(patterns.get("/api/resource/{id}").pattern()).isEqualTo("/api/resource/([^/]+)");
    }
}

