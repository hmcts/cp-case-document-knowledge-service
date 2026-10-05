package uk.gov.hmcts.cp.cdk.correlation;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Verifies outbound correlation-ID propagation (DD-43183 Story 2, AC-001, AC-004(ii)) against a
 * live compose stack: an inbound correlation value must reach a WireMock-stubbed downstream call
 * verbatim, on both outbound headers, and the response header must still carry it afterwards —
 * proving {@link CorrelationIdInterceptor} neither substitutes nor destroys the ambient value.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrelationPropagationHttpLiveTest extends AbstractHttpLiveTest {

    private static final MediaType VND_BY_CASE =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process-by-case+json");
    private static final String CJSCPPUID = "CJSCPPUID";
    private static final String CPPUID_VALUE = "a085e359-6069-4694-8820-7810e7dfe762";
    private static final String COURT_DOCS_PATH =
            "/progression-query-api/query/api/rest/progression/courtdocumentsearch";

    @BeforeEach
    void setUp() {
        configureFor("localhost", 8089);
    }

    @Test
    void correlationId_propagatesVerbatimToProgression_andSurvivesOnTheResponseAfterward() {
        final String correlationId = UUID.randomUUID().toString();
        final UUID caseId = UUID.randomUUID();

        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_BY_CASE);
        headers.setAccept(List.of(VND_BY_CASE));
        headers.set(CJSCPPUID, CPPUID_VALUE);
        headers.set(CorrelationIds.HEADER_CPP, correlationId);

        final ResponseEntity<String> response = http.exchange(
                baseUrl + "/ingestions/start-by-case",
                HttpMethod.POST,
                new HttpEntity<>("{ \"caseId\": \"%s\" }".formatted(caseId), headers),
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst(CorrelationIds.HEADER_X_CORRELATION_ID))
                .as("the response header still carries the sent correlation value after an outbound call")
                .isEqualTo(correlationId);

        final List<LoggedRequest> outboundCalls = findAll(getRequestedFor(urlPathEqualTo(COURT_DOCS_PATH)));
        assertThat(outboundCalls).as("at least one outbound call to Progression's court-doc-search").isNotEmpty();
        final LoggedRequest last = outboundCalls.get(outboundCalls.size() - 1);
        assertThat(last.getHeader(CorrelationIds.HEADER_CPP))
                .as("outbound CPPCLIENTCORRELATIONID header")
                .isEqualTo(correlationId);
        assertThat(last.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID))
                .as("outbound X-Correlation-Id header")
                .isEqualTo(correlationId);
    }
}
