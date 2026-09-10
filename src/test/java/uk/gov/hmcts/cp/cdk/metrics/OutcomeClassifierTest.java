package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpResponse;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("OutcomeClassifier tests (DD-43182 Story 3, ADR-003)")
class OutcomeClassifierTest {

    @Test
    @DisplayName("a 4xx HttpStatusCodeException classifies as client_error")
    void classifiesHttpClientError() {
        final Exception e = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                null, null, null);

        assertThat(OutcomeClassifier.classify(e)).isEqualTo(CdkMeters.OUTCOME_CLIENT_ERROR);
    }

    @Test
    @DisplayName("a 5xx HttpStatusCodeException classifies as server_error")
    void classifiesHttpServerError() {
        final Exception e = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable",
                null, null, null);

        assertThat(OutcomeClassifier.classify(e)).isEqualTo(CdkMeters.OUTCOME_SERVER_ERROR);
    }

    @Test
    @DisplayName("RagClientException wrapping a 4xx classifies via its cause, never blanket server_error")
    void ragClientExceptionWrappingClientErrorClassifiesByCause() {
        final Exception cause = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                null, null, null);
        final RuntimeException ragException = new RuntimeException("RAG API error", cause);

        assertThat(OutcomeClassifier.classify(ragException)).isEqualTo(CdkMeters.OUTCOME_CLIENT_ERROR);
    }

    @Test
    @DisplayName("RagClientException wrapping a 5xx classifies via its cause")
    void ragClientExceptionWrappingServerErrorClassifiesByCause() {
        final Exception cause = HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Boom",
                null, null, null);
        final RuntimeException ragException = new RuntimeException("RAG API error", cause);

        assertThat(OutcomeClassifier.classify(ragException)).isEqualTo(CdkMeters.OUTCOME_SERVER_ERROR);
    }

    @Test
    @DisplayName("a ResourceAccessException wrapping SocketTimeoutException classifies as timeout, "
            + "walking through the intermediate wrapper (CDKS's real depth-3 shape)")
    void resourceAccessExceptionWrappingSocketTimeoutClassifiesAsTimeout() {
        final RuntimeException ragException = new RuntimeException("RAG API error",
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThat(OutcomeClassifier.classify(ragException)).isEqualTo(CdkMeters.OUTCOME_TIMEOUT);
    }

    @Test
    @DisplayName("a bare ConnectTimeoutException classifies as timeout")
    void connectTimeoutExceptionClassifiesAsTimeout() {
        assertThat(OutcomeClassifier.classify(new ConnectTimeoutException("connect timed out")))
                .isEqualTo(CdkMeters.OUTCOME_TIMEOUT);
    }

    @Test
    @DisplayName("a bare ConnectionRequestTimeoutException classifies as timeout")
    void connectionRequestTimeoutExceptionClassifiesAsTimeout() {
        assertThat(OutcomeClassifier.classify(new ConnectionRequestTimeoutException("no connection available")))
                .isEqualTo(CdkMeters.OUTCOME_TIMEOUT);
    }

    @Test
    @DisplayName("a bare java.util.concurrent.TimeoutException classifies as timeout")
    void concurrentTimeoutExceptionClassifiesAsTimeout() {
        assertThat(OutcomeClassifier.classify(new TimeoutException("timed out")))
                .isEqualTo(CdkMeters.OUTCOME_TIMEOUT);
    }

    @Test
    @DisplayName("a JSON-parse-failure-shaped exception with no HTTP status and not a timeout classifies as error")
    void genericFailureClassifiesAsError() {
        assertThat(OutcomeClassifier.classify(new IllegalStateException("could not parse response body")))
                .isEqualTo(CdkMeters.OUTCOME_ERROR);
    }

    @Test
    @DisplayName("Azure SDK HttpResponseException 4xx classifies as client_error")
    void azureHttpResponseException4xxClassifiesAsClientError() {
        final HttpResponse response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.getStatusCode()).thenReturn(404);
        final HttpResponseException e = new HttpResponseException("not found", response);

        assertThat(OutcomeClassifier.classify(e)).isEqualTo(CdkMeters.OUTCOME_CLIENT_ERROR);
    }

    @Test
    @DisplayName("Azure SDK HttpResponseException 5xx classifies as server_error")
    void azureHttpResponseException5xxClassifiesAsServerError() {
        final HttpResponse response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.getStatusCode()).thenReturn(503);
        final HttpResponseException e = new HttpResponseException("unavailable", response);

        assertThat(OutcomeClassifier.classify(e)).isEqualTo(CdkMeters.OUTCOME_SERVER_ERROR);
    }

    @Test
    @DisplayName("an HttpResponseException with a null response (Azure SDK edge case) does not throw — "
            + "classifies as error rather than NPE-ing")
    void azureHttpResponseExceptionWithNullResponseDoesNotThrow() {
        final HttpResponseException e = new HttpResponseException("boom", null);

        assertThat(OutcomeClassifier.classify(e)).isEqualTo(CdkMeters.OUTCOME_ERROR);
    }

    @Test
    @DisplayName("a cyclic cause chain is guarded against and resolves to error, never loops forever")
    void cyclicCauseChainIsGuarded() {
        assertThat(OutcomeClassifier.classify(new SelfCyclingThrowable())).isEqualTo(CdkMeters.OUTCOME_ERROR);
    }

    /** A cause chain that cycles back to itself — overriding getCause() sidesteps the JDK's
     * private, module-encapsulated Throwable.cause field, which reflection cannot reach here. */
    private static final class SelfCyclingThrowable extends RuntimeException {
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    @Test
    @DisplayName("a status-bearing cause deeper than the 5-layer bound is mis-tagged error, "
            + "not a wrong positive classification — stated as an accepted degradation")
    void beyondDepthBoundClassifiesAsErrorNotAsAGuess() {
        Throwable current = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                null, null, null);
        for (int i = 0; i < 6; i++) {
            current = new RuntimeException("wrapper " + i, current);
        }

        assertThat(OutcomeClassifier.classify(current)).isEqualTo(CdkMeters.OUTCOME_ERROR);
    }

    @Test
    @DisplayName("CDKS's real deepest chain (depth 3) is well within the bound and classifies correctly")
    void realDepthThreeChainClassifiesCorrectly() {
        final SocketTimeoutException socketTimeout = new SocketTimeoutException("Read timed out");
        final Throwable resourceAccess = new ResourceAccessException("I/O error", socketTimeout);
        final Throwable ragClientException = new RuntimeException("RAG API error", resourceAccess);

        assertThat(OutcomeClassifier.classify(ragClientException)).isEqualTo(CdkMeters.OUTCOME_TIMEOUT);
    }
}
