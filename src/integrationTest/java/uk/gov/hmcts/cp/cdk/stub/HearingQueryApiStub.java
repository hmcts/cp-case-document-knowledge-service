package uk.gov.hmcts.cp.cdk.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.apache.http.HttpStatus.SC_OK;

public class HearingQueryApiStub {

    private static final String HEARINGS_PATH = "/hearing-query-api/query/api/rest/hearing/hearings";
    public static final String APPLICATION_JSON = "application/json";

    public static void stubGetHearingsReturnsEmptyHearingSummaries(final String courtCentreId, final String roomId) {
        stubFor(get(urlPathEqualTo(HEARINGS_PATH))
                .withQueryParam("courtCentreId", equalTo(courtCentreId))
                .withQueryParam("roomId", equalTo(roomId))
                .withQueryParam("date", matching(".*"))
                .willReturn(aResponse()
                        .withStatus(SC_OK)
                        .withHeader("Content-Type", APPLICATION_JSON)
                        .withBody("{\"hearingSummaries\":[]}")
                ));
    }
}
