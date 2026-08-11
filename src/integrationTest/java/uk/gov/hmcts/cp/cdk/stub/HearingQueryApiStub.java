package uk.gov.hmcts.cp.cdk.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.apache.http.HttpStatus.SC_OK;

import java.util.UUID;

public class HearingQueryApiStub {

    private static final String HEARINGS_PATH = "/hearing-query-api/query/api/rest/hearing/hearings";
    private static final String HEARING_CASES_FOR_DAY_PATH = "/hearing-query-api/query/api/rest/hearing/hearing-cases-for-day";
    public static final String APPLICATION_JSON = "application/json";
    private static final String DATE = "date";
    private static final String CONTENT_TYPE = "Content-Type";

    public static void stubGetHearingsReturnsEmptyHearingSummaries(final String courtCentreId, final String roomId) {
        stubFor(get(urlPathEqualTo(HEARINGS_PATH))
                .withQueryParam("courtCentreId", equalTo(courtCentreId))
                .withQueryParam("roomId", equalTo(roomId))
                .withQueryParam(DATE, matching(".*"))
                .willReturn(aResponse()
                        .withStatus(SC_OK)
                        .withHeader("Content-Type", APPLICATION_JSON)
                        .withBody("{\"hearingSummaries\":[]}")
                ));
    }

    public static void stubGetHearingCasesForDayReturnsEmptyHearingCases() {
        stubFor(get(urlPathEqualTo(HEARING_CASES_FOR_DAY_PATH))
                .withQueryParam("date", matching(".*"))
                .willReturn(aResponse()
                        .withStatus(SC_OK)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON)
                        .withBody("{\"hearingCases\":[]}")
                ));
    }

    public static void stubGetHearingCasesForDayReturnsEmptyHearingCasesWithDelay(final int fixedDelayMs) {
        stubFor(get(urlPathEqualTo(HEARING_CASES_FOR_DAY_PATH))
                .withQueryParam("date", matching(".*"))
                .willReturn(aResponse()
                        .withStatus(SC_OK)
                        .withHeader("Content-Type", APPLICATION_JSON)
                        .withFixedDelay(fixedDelayMs)
                        .withBody("{\"hearingCases\":[]}")
                ));
    }

    public static void stubGetHearingCasesForDayReturnsHearingCase(final String courtCentreId, final String courtRoomId,
                                                                    final String caseId) {
        stubFor(get(urlPathEqualTo(HEARING_CASES_FOR_DAY_PATH))
                .withQueryParam("date", matching(".*"))
                .willReturn(aResponse()
                        .withStatus(SC_OK)
                        .withHeader("Content-Type", APPLICATION_JSON)
                        .withBody("""
                                {"hearingCases":[{
                                  "courtCentreId":"%s",
                                  "courtRoomId":"%s",
                                  "hearingDate":"2026-07-15",
                                  "hearingId":"%s",
                                  "prosecutionCases":["%s"]
                                }]}"""
                                .formatted(courtCentreId, courtRoomId, UUID.randomUUID(), caseId))
                ));
    }
}
