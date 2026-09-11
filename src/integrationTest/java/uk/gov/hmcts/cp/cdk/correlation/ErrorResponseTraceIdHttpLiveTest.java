package uk.gov.hmcts.cp.cdk.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Verifies {@code ErrorResponse.traceId} carries the same value as the {@code X-Correlation-Id}
 * response header (DD-43183 Story 4, AC-001/AC-002's automatable clause). The third clause — that
 * the same value also appears on the log lines for the request — is MV-1, manual (OQ-102), and is
 * not asserted here.
 */
class ErrorResponseTraceIdHttpLiveTest extends AbstractHttpLiveTest {

    private static final MediaType VND_BY_CASE =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process-by-case+json");
    private static final String CJSCPPUID = "CJSCPPUID";
    private static final String CPPUID_VALUE = "a085e359-6069-4694-8820-7810e7dfe762";

    @Test
    void errorResponse_traceId_equalsTheXCorrelationIdResponseHeaderAndTheSentValue() {
        final String correlationId = UUID.randomUUID().toString();
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_BY_CASE);
        headers.setAccept(List.of(VND_BY_CASE));
        headers.set(CJSCPPUID, CPPUID_VALUE);
        headers.set(CorrelationIds.HEADER_CPP, correlationId);

        final HttpClientErrorException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientErrorException.class, () -> http.exchange(
                        baseUrl + "/ingestions/start-by-case",
                        HttpMethod.POST,
                        new HttpEntity<>("not valid json at all", headers),
                        String.class
                ));

        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        final String responseHeaderCorrelationId = thrown.getResponseHeaders() == null ? null
                : thrown.getResponseHeaders().getFirst(CorrelationIds.HEADER_X_CORRELATION_ID);
        assertThat(responseHeaderCorrelationId).isEqualTo(correlationId);

        final String body = thrown.getResponseBodyAsString();
        assertThat(body).as("error body must carry traceId equal to the sent correlation id")
                .contains("\"traceId\":\"" + correlationId + "\"");
    }
}
