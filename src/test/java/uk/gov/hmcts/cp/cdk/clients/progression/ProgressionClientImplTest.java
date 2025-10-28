package uk.gov.hmcts.cp.cdk.clients.progression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.cdk.clients.common.CdkClientProperties;
import uk.gov.hmcts.cp.cdk.clients.progression.mapper.ProgressionDtoMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
    void getMaterialDownloadUrl_fetchesAndExtractsUrl() {
        final var rootProps = new CdkClientProperties(
                "http://localhost:8080",
                new CdkClientProperties.Headers("CJSCPPUID")
        );
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
                .andExpect(header("CJSCPPUID", "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.material-content+json"))
                .andRespond(withSuccess("{\"url\":\"https://signed.example\"}", MediaType.APPLICATION_JSON));

        final var url = client.getMaterialDownloadUrl(materialId);
        server.verify();
        assertThat(url).contains("https://signed.example");
    }

    @Test
    void getCourtDocuments_returnsLatestMaterialInfo() {
        final var rootProps = new CdkClientProperties(
                "http://localhost:8080",
                new CdkClientProperties.Headers("CJSCPPUID")
        );
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
                .andExpect(header("CJSCPPUID", "system"))
                .andExpect(header("Accept", "application/vnd.progression.query.courtdocuments+json"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        final var latest = client.getCourtDocuments(caseId);
        server.verify();
        assertThat(latest).isPresent();
        assertThat(latest.get().materialId()).isEqualTo("m2");
        assertThat(latest.get().caseIds()).containsExactly("CASE-9");
    }
}
