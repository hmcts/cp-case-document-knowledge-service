package uk.gov.hmcts.cp.cdk.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the inbound correlation-header convention, precedence, generation fallback and response
 * echo (DD-43183 Story 1) against a live compose stack.
 */
class CorrelationIdHttpLiveTest extends AbstractHttpLiveTest {

    private static final String ACTUATOR_HEALTH = "/actuator/health";

    @Test
    void canonicalHeader_isEchoedOnTheResponse() {
        final String cid = UUID.randomUUID().toString();
        final HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIds.HEADER_CPP, cid);

        final ResponseEntity<String> response = exchange(headers);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst(CorrelationIds.HEADER_X_CORRELATION_ID)).isEqualTo(cid);
    }

    @Test
    void aliasHeader_isHonouredWhenCanonicalAbsent() {
        final String cid = UUID.randomUUID().toString();
        final HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIds.HEADER_X_CORRELATION_ID, cid);

        final ResponseEntity<String> response = exchange(headers);

        assertThat(response.getHeaders().getFirst(CorrelationIds.HEADER_X_CORRELATION_ID)).isEqualTo(cid);
    }

    @Test
    void canonicalHeader_winsOverAlias_whenBothPresentWithDifferentValues() {
        final String canonical = UUID.randomUUID().toString();
        final String alias = UUID.randomUUID().toString();
        final HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIds.HEADER_CPP, canonical);
        headers.set(CorrelationIds.HEADER_X_CORRELATION_ID, alias);

        final ResponseEntity<String> response = exchange(headers);

        assertThat(response.getHeaders().getFirst(CorrelationIds.HEADER_X_CORRELATION_ID)).isEqualTo(canonical);
    }

    @Test
    void noHeaderSent_generatesANonBlankValue_stillEchoedOnTheResponse() {
        final ResponseEntity<String> response = exchange(new HttpHeaders());

        final String echoed = response.getHeaders().getFirst(CorrelationIds.HEADER_X_CORRELATION_ID);
        assertThat(echoed).isNotBlank();
    }

    @Test
    void traceIdAndSpanIdResponseHeaders_areNoLongerEchoed_evenWhenSentInbound() {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("traceId", "1234-1234");
        headers.set("spanId", "5678-5678");

        final ResponseEntity<String> response = exchange(headers);

        assertThat(response.getHeaders().getFirst("traceId")).isNull();
        assertThat(response.getHeaders().getFirst("spanId")).isNull();
    }

    private ResponseEntity<String> exchange(final HttpHeaders headers) {
        return http.exchange(baseUrl + ACTUATOR_HEALTH, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }
}
