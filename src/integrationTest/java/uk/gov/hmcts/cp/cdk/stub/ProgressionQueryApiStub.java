package uk.gov.hmcts.cp.cdk.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.apache.http.HttpStatus.SC_OK;

public class ProgressionQueryApiStub {

    private static final String COURT_DOCUMENT_SEARCH_PATH = "/progression-query-api/query/api/rest/progression/courtdocumentsearch";
    public static final String APPLICATION_JSON = "application/json";

    public static void stubGetCourtDocumentsForAllDefendantsReturnsEmpty(final String caseId) {
        stubFor(get(urlPathEqualTo(COURT_DOCUMENT_SEARCH_PATH))
                .withQueryParam("caseId", equalTo(caseId))
                .willReturn(aResponse()
                        .withStatus(SC_OK)
                        .withHeader("Content-Type", APPLICATION_JSON)
                        .withBody("{\"documentIndices\":[]}")
                ));
    }
}
