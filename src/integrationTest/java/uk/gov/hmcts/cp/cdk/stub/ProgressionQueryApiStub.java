package uk.gov.hmcts.cp.cdk.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.apache.http.HttpStatus.SC_OK;

public class ProgressionQueryApiStub {

    private static final String PROSECUTION_CASE_PATH_PREFIX = "/progression-query-api/query/api/rest/progression/prosecutioncases/";
    public static final String APPLICATION_JSON = "application/json";

    public static void stubGetProsecutionCaseEligibilityInfoReturnsEmpty(final String caseId) {
        stubFor(get(urlPathEqualTo(PROSECUTION_CASE_PATH_PREFIX + caseId))
                .willReturn(aResponse()
                        .withStatus(SC_OK)
                        .withHeader("Content-Type", APPLICATION_JSON)
                        .withBody("{}")
                ));
    }
}
