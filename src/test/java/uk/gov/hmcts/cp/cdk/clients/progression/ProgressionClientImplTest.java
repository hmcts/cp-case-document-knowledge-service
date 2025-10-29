package uk.gov.hmcts.cp.cdk.clients.progression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.clients.progression.mapper.ProgressionDtoMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("Progression Client Impl tests")
class ProgressionClientImplTest {

    private RestClient restClient;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        // Bind MockRestServiceServer to a RestTemplate
        final RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        // Build RestClient that uses the SAME request factory as the bound RestTemplate
        restClient = RestClient.builder()
                .baseUrl("http://localhost:8080")
                .requestFactory(restTemplate.getRequestFactory())
                .build();
    }

    @Test
    @DisplayName("Get Material Download Url fetches And Extracts Url")
    void getMaterialDownloadUrl_fetchesAndExtractsUrl() {
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final String headerName = rootProps.headers().cjsCppuid();

        final var cfg = new ProgressionClientConfig(
                "acc",
                "DOC-41",
                "/progression-query-api/query/api/rest/progression/courtdocumentsearch",
                "/progression-query-api/query/api/rest/progression/material/{materialId}/content",
                "application/vnd.progression.query.courtdocuments+json",
                "application/vnd.progression.query.material-content+json"
        );
        final var client = new ProgressionClientImpl(restClient, rootProps, cfg, new ProgressionDtoMapper(cfg));

        final UUID materialId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        server.expect(once(),
                        requestTo("http://localhost:8080/progression-query-api/query/api/rest/progression/material/" + materialId + "/content"))
                .andExpect(header(headerName, "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.material-content+json"))
                .andRespond(withSuccess("{\"url\":\"https://signed.example\"}", MediaType.APPLICATION_JSON));

        final var url = client.getMaterialDownloadUrl(materialId);
        server.verify();
        assertThat(url).contains("https://signed.example");
    }

    @Test
    @DisplayName("Get Null Material Download Url fetches And Extracts Url")
    void getNullMaterialDownloadUrl_fetchesAndExtractsUrl() {
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final String headerName = rootProps.headers().cjsCppuid();

        final var cfg = new ProgressionClientConfig(
                "acc",
                "DOC-41",
                "/progression-query-api/query/api/rest/progression/courtdocumentsearch",
                "/progression-query-api/query/api/rest/progression/material/{materialId}/content",
                "application/vnd.progression.query.courtdocuments+json",
                "application/vnd.progression.query.material-content+json"
        );
        final var client = new ProgressionClientImpl(restClient, rootProps, cfg, new ProgressionDtoMapper(cfg));

        final UUID materialId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        server.expect(once(),
                        requestTo("http://localhost:8080/progression-query-api/query/api/rest/progression/material/" + materialId + "/content"))
                .andExpect(header(headerName, "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.material-content+json"))
                .andRespond(withSuccess("{\"url\":\"   \"}", MediaType.APPLICATION_JSON));

        final var url = client.getMaterialDownloadUrl(materialId);
        server.verify();
        assertThat(url).isEmpty();
    }

    @Test
    @DisplayName("Get Court Documents returns Latest Material Info")
    void getCourtDocuments_returnsLatestMaterialInfo() {
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final String headerName = rootProps.headers().cjsCppuid();

        final var cfg = new ProgressionClientConfig(
                "acc",
                "DOC-41",
                "/progression-query-api/query/api/rest/progression/courtdocumentsearch",
                "/progression-query-api/query/api/rest/progression/material/{materialId}/content",
                "application/vnd.progression.query.courtdocuments+json",
                "application/vnd.progression.query.material-content+json"
        );
        final var client = new ProgressionClientImpl(restClient, rootProps, cfg, new ProgressionDtoMapper(cfg));

        final UUID caseId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        final String responseJson =
                "{\n" +
                        "  \"documentIndices\": [\n" +
                        "    {\n" +
                        "      \"caseIds\": [\"CASE-9\"],\n" +
                        "      \"document\": {\n" +
                        "        \"documentTypeId\": \"DOC-41\",\n" +
                        "        \"documentTypeDescription\": \"Some Doc\",\n" +
                        "        \"materials\": [\n" +
                        "          { \"id\": \"m1\", \"uploadDateTime\": \"2024-01-01T10:15:30Z\" },\n" +
                        "          { \"id\": \"m2\", \"uploadDateTime\": \"2024-03-01T10:15:30Z\" }\n" +
                        "        ]\n" +
                        "      }\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}";

        server.expect(once(),
                        requestTo("http://localhost:8080/progression-query-api/query/api/rest/progression/courtdocumentsearch?caseId=" + caseId))
                .andExpect(header(headerName, "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.courtdocuments+json"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        final var latest = client.getCourtDocuments(caseId);
        server.verify();
        assertThat(latest).isPresent();
        assertThat(latest.get().materialId()).isEqualTo("m2");
        assertThat(latest.get().caseIds()).containsExactly("CASE-9");
    }

    @Test
    @DisplayName("Get Court Documents returns Empty Document Latest Material Info")
    void getCourtDocuments_returnsEmptyDocumentLatestMaterialInfo() {
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final String headerName = rootProps.headers().cjsCppuid();

        final var cfg = new ProgressionClientConfig(
                "acc",
                "DOC-41",
                "/progression-query-api/query/api/rest/progression/courtdocumentsearch",
                "/progression-query-api/query/api/rest/progression/material/{materialId}/content",
                "application/vnd.progression.query.courtdocuments+json",
                "application/vnd.progression.query.material-content+json"
        );
        final var client = new ProgressionClientImpl(restClient, rootProps, cfg, new ProgressionDtoMapper(cfg));

        final UUID caseId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        final String responseJson = "{ \"documentIndices\": [] }";

        server.expect(once(),
                        requestTo("http://localhost:8080/progression-query-api/query/api/rest/progression/courtdocumentsearch?caseId=" + caseId))
                .andExpect(header(headerName, "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.courtdocuments+json"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        final var latest = client.getCourtDocuments(caseId);
        server.verify();
        assertThat(latest).isEmpty();
    }

    @Test
    @DisplayName("Get Court Documents returns Null Document Latest Material Info")
    void getCourtDocuments_returnsNullDocumentLatestMaterialInfo() {
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final String headerName = rootProps.headers().cjsCppuid();

        final var cfg = new ProgressionClientConfig(
                "acc",
                "DOC-41",
                "/progression-query-api/query/api/rest/progression/courtdocumentsearch",
                "/progression-query-api/query/api/rest/progression/material/{materialId}/content",
                "application/vnd.progression.query.courtdocuments+json",
                "application/vnd.progression.query.material-content+json"
        );
        final var client = new ProgressionClientImpl(restClient, rootProps, cfg, new ProgressionDtoMapper(cfg));

        final UUID caseId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        final String responseJson = "{ }";

        server.expect(once(),
                        requestTo("http://localhost:8080/progression-query-api/query/api/rest/progression/courtdocumentsearch?caseId=" + caseId))
                .andExpect(header(headerName, "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.courtdocuments+json"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        final var latest = client.getCourtDocuments(caseId);
        server.verify();
        assertThat(latest).isEmpty();
    }

    @Test
    @DisplayName("Get Court Documents returns no IDPC Latest Material Info")
    void getCourtDocuments_returnsnoIDPCLatestMaterialInfo() {
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final String headerName = rootProps.headers().cjsCppuid();

        final var cfg = new ProgressionClientConfig(
                "acc",
                "DOC-41",
                "/progression-query-api/query/api/rest/progression/courtdocumentsearch",
                "/progression-query-api/query/api/rest/progression/material/{materialId}/content",
                "application/vnd.progression.query.courtdocuments+json",
                "application/vnd.progression.query.material-content+json"
        );
        final var client = new ProgressionClientImpl(restClient, rootProps, cfg, new ProgressionDtoMapper(cfg));

        final UUID caseId = UUID.fromString("f89fa869-1d5b-47e2-a98d-cf022b99c305");

        final String responseJson =
                "{\n" +
                        "  \"documentIndices\": [\n" +
                        "    {\n" +
                        "      \"caseIds\": [\"f89fa869-1d5b-47e2-a98d-cf022b99c305\"],\n" +
                        "      \"document\": {\n" +
                        "        \"documentTypeId\": \"41be14e8-9df5-4b08-80b0-1e670bc80a5b\",\n" +
                        "        \"materials\": []\n" +
                        "      }\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}";

        server.expect(once(),
                        requestTo("http://localhost:8080/progression-query-api/query/api/rest/progression/courtdocumentsearch?caseId=" + caseId))
                .andExpect(header(headerName, "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.courtdocuments+json"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        final var latest = client.getCourtDocuments(caseId);
        server.verify();
        assertThat(latest).isEmpty();
    }

    @Test
    @DisplayName("Get Court Documents returns no Material Latest Material Info")
    void getCourtDocuments_returnsnoMaterialLatestMaterialInfo() {
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final String headerName = rootProps.headers().cjsCppuid();

        final var cfg = new ProgressionClientConfig(
                "acc",
                "DOC-41",
                "/progression-query-api/query/api/rest/progression/courtdocumentsearch",
                "/progression-query-api/query/api/rest/progression/material/{materialId}/content",
                "application/vnd.progression.query.courtdocuments+json",
                "application/vnd.progression.query.material-content+json"
        );
        final var client = new ProgressionClientImpl(restClient, rootProps, cfg, new ProgressionDtoMapper(cfg));

        final UUID caseId = UUID.fromString("f89fa869-1d5b-47e2-a98d-cf022b99c305");

        final String responseJson =
                "{\n" +
                        "  \"documentIndices\": [\n" +
                        "    {\n" +
                        "      \"caseIds\": [\"CASE-1\"],\n" +
                        "      \"document\": {\n" +
                        "        \"documentTypeId\": \"DOC-41\",\n" +
                        "        \"documentTypeDescription\": \"Some Doc\",\n" +
                        "        \"materials\": null\n" +
                        "      }\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}";

        server.expect(once(),
                        requestTo("http://localhost:8080/progression-query-api/query/api/rest/progression/courtdocumentsearch?caseId=" + caseId))
                .andExpect(header(headerName, "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.courtdocuments+json"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        final var latest = client.getCourtDocuments(caseId);
        server.verify();
        assertThat(latest).isEmpty(); // Optional.empty() branch
    }
}
