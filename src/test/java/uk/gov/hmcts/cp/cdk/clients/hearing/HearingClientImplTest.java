package uk.gov.hmcts.cp.cdk.clients.hearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.cdk.clients.common.CdkClientProperties;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.mapper.HearingDtoMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
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
        // Arrange
        final var rootProps = new CdkClientProperties(
                "http://localhost:8080",
                new CdkClientProperties.Headers("CJSCPPUID")
        );
        final var hearingCfg = new HearingClientConfig(
                "application/vnd.hearing.get.hearings+json",
                "/hearing-query-api/query/api/rest/hearing/hearings"
        );
        final var client = new HearingClientImpl(restClient, rootProps, hearingCfg, new HearingDtoMapper());

        final String responseBody = """
            {
              "hearingSummaries": [
                { "prosecutionCaseSummaries": [ { "id": "CASE-1" }, { "id": "CASE-2" } ] }
              ]
            }
            """;

        // Expect
        server.expect(once(),
                        requestTo(allOf(
                                containsString("/hearing-query-api/query/api/rest/hearing/hearings"),
                                containsString("courtCentreId=C001"),
                                containsString("roomId=R12"),
                                containsString("date=2025-10-29")
                        )))
                .andExpect(header("CJSCPPUID", "system"))
                .andExpect(header("Accept", "application/vnd.hearing.get.hearings+json"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        // Act
        final List<HearingSummariesInfo> infos =
                client.getHearingsAndCases("C001", "R12", LocalDate.of(2025, 10, 29));

        // Assert
        server.verify();
        assertThat(infos).extracting(HearingSummariesInfo::caseId)
                .containsExactly("CASE-1", "CASE-2");
    }

    @Test
    @DisplayName("Get Hearings And Cases empty Response Returns Empty List")
    void getHearingsAndCases_emptyResponseReturnsEmptyList() {
        // Arrange
        final var rootProps = new CdkClientProperties(
                "http://localhost:8080",
                new CdkClientProperties.Headers("CJSCPPUID")
        );
        final var hearingCfg = new HearingClientConfig(
                "application/vnd.hearing.get.hearings+json",
                "/hearing-query-api/query/api/rest/hearing/hearings"
        );
        final var client = new HearingClientImpl(restClient, rootProps, hearingCfg, new HearingDtoMapper());

        // Expect
        server.expect(once(),
                        requestTo("http://localhost:8080/hearing-query-api/query/api/rest/hearing/hearings" +
                                "?courtCentreId=C001&roomId=R12&date=2025-10-29"))
                .andExpect(header("CJSCPPUID", "system"))
                .andExpect(header("Accept", "application/vnd.hearing.get.hearings+json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // Act
        final List<HearingSummariesInfo> infos =
                client.getHearingsAndCases("C001", "R12", LocalDate.of(2025, 10, 29));

        // Assert
        server.verify();
        assertThat(infos).isEmpty();
    }
}
