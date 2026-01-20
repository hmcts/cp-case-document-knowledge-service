package uk.gov.hmcts.cp.cdk.batch.clients.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClientConfig;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClientImpl;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.mapper.HearingDtoMapper;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@DisplayName("Hearing Client Impl tests")
class HearingClientImplTest {

    private RestClient restClient;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        // Bind MockRestServiceServer to a RestTemplate
        final RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        // Use the same request factory for RestClient so the server intercepts calls
        restClient = RestClient.builder()
                .baseUrl("http://localhost:8080")
                .requestFactory(restTemplate.getRequestFactory())
                .build();
    }

    @Test
    @DisplayName("Get Hearings And Cases collects Ids And Maps")
    void getHearingsAndCases_collectsIdsAndMaps() {

        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final var hearingCfg = new HearingClientConfig(
                "application/vnd.hearing.get.hearings+json",
                "/hearing-query-api/query/api/rest/hearing/hearings"
        );
        final IngestionProperties ingestionProps = mock(IngestionProperties.class, Mockito.RETURNS_DEEP_STUBS);
        final var client = new HearingClientImpl(restClient, rootProps, hearingCfg, new HearingDtoMapper(ingestionProps));

        final String headerName = rootProps.headers().cjsCppuid(); // <-- use configured name
        final String responseBody = """
                {
                  "hearingSummaries": [
                    { 
                      "prosecutionCaseSummaries": [
                        { "id": "CASE-1", "defendants": [ { "id": "D1" } ] },
                        { "id": "CASE-2", "defendants": [ { "id": "D2" } ] }
                      ]
                    }
                  ]
                }
                """;

        server.expect(once(),
                        requestTo(allOf(
                                containsString("/hearing-query-api/query/api/rest/hearing/hearings"),
                                containsString("courtCentreId=C001"),
                                containsString("roomId=R12"),
                                containsString("date=2025-10-29")
                        )))
                .andExpect(header(headerName, "userId")) // <-- assert configured header
                .andExpect(header("Accept", "application/vnd.hearing.get.hearings+json"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        final List<HearingSummariesInfo> infos =
                client.getHearingsAndCases("C001", "R12", LocalDate.of(2025, 10, 29), "userId");

        server.verify();
        assertThat(infos).extracting(HearingSummariesInfo::caseId)
                .containsExactly("CASE-1", "CASE-2");
    }

    @Test
    @DisplayName("Get Hearings And Cases empty Response Returns Empty List")
    void getHearingsAndCases_emptyResponseReturnsEmptyList() {
        // Arrange
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final var hearingCfg = new HearingClientConfig(
                "application/vnd.hearing.get.hearings+json",
                "/hearing-query-api/query/api/rest/hearing/hearings"
        );
        final IngestionProperties ingestionProps = mock(IngestionProperties.class, Mockito.RETURNS_DEEP_STUBS);
        final var client = new HearingClientImpl(restClient, rootProps, hearingCfg, new HearingDtoMapper(ingestionProps));

        final String headerName = rootProps.headers().cjsCppuid(); // <-- use configured name

        // Expect
        server.expect(once(),
                        requestTo("http://localhost:8080/hearing-query-api/query/api/rest/hearing/hearings" +
                                "?courtCentreId=C001&roomId=R12&date=2025-10-29"))
                .andExpect(header(headerName, "userId")) // <-- assert configured header
                .andExpect(header("Accept", "application/vnd.hearing.get.hearings+json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        final List<HearingSummariesInfo> infos =
                client.getHearingsAndCases("C001", "R12", LocalDate.of(2025, 10, 29), "userId");

        server.verify();
        assertThat(infos).isEmpty();
    }


    @Test
    @DisplayName("Get Hearings And Cases ignores cases with more than one defendant")
    void getHearingsAndCases_ignoresCasesWithMultipleDefendants() {
        var rootProps = new CQRSClientProperties(
                "http://localhost:8080",
                3000, 15000,
                new CQRSClientProperties.Headers("X-CJSCPPUID")
        );
        final var hearingCfg = new HearingClientConfig(
                "application/vnd.hearing.get.hearings+json",
                "/hearing-query-api/query/api/rest/hearing/hearings"
        );
        final IngestionProperties ingestionProps = mock(IngestionProperties.class, Mockito.RETURNS_DEEP_STUBS);
        final var client = new HearingClientImpl(restClient, rootProps, hearingCfg, new HearingDtoMapper(ingestionProps));

        final String headerName = rootProps.headers().cjsCppuid();

        final String responseBody = """
                {
                  "hearingSummaries": [
                    {
                      "prosecutionCaseSummaries": [
                        {
                          "id": "CASE-MULTI",
                          "defendants": [
                            { "id": "D1" },
                            { "id": "D2" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        server.expect(once(),
                        requestTo(allOf(
                                containsString("/hearing-query-api/query/api/rest/hearing/hearings"),
                                containsString("courtCentreId=C001"),
                                containsString("roomId=R12"),
                                containsString("date=2025-10-29")
                        )))
                .andExpect(header(headerName, "userId"))
                .andExpect(header("Accept", "application/vnd.hearing.get.hearings+json"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        final List<HearingSummariesInfo> infos =
                client.getHearingsAndCases("C001", "R12", LocalDate.of(2025, 10, 29), "userId");

        server.verify();
        assertThat(infos)
                .as("Cases with 2 defendants must be filtered out")
                .isEmpty();
    }

}
