package uk.gov.hmcts.cp.cdk.metrics;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import com.azure.core.exception.HttpResponseException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Classifies an outbound-call failure into one of {@link CdkMeters}'s five {@code outcome} values
 * by walking the exception <em>cause chain</em> (DD-43182 Story 3, ADR-003), depth-bounded at 5 and
 * cycle-guarded.
 *
 * <p>{@code RagClientException} wraps both 4xx/5xx HTTP errors and timeouts identically by Java
 * type — the original exception is always preserved as the <em>cause</em>, so classifying by the
 * outermost type alone would make {@code client_error}/{@code server_error}/{@code timeout}
 * permanently unreachable for RAG. Walking the chain is what unblocks this.
 *
 * <p>At and beyond the depth bound, the outcome is the generic {@link CdkMeters#OUTCOME_ERROR} —
 * "we could not tell", never a wrong positive classification. CDKS's own deepest chain today is
 * depth 3 ({@code RagClientException → ResourceAccessException → SocketTimeoutException}).
 */
public final class OutcomeClassifier {

    private static final int MAX_DEPTH = 5;

    private OutcomeClassifier() {
        throw new AssertionError("No instances");
    }

    public static String classify(final Throwable throwable) {
        final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_DEPTH) {
            if (!visited.add(current)) {
                break; // cycle detected
            }
            final String outcome = classifyOne(current);
            if (outcome != null) {
                return outcome;
            }
            current = current.getCause();
            depth++;
        }
        return CdkMeters.OUTCOME_ERROR;
    }

    private static String classifyOne(final Throwable throwable) {
        if (throwable instanceof HttpStatusCodeException httpStatusCodeException) {
            return classifyHttpStatus(httpStatusCodeException.getStatusCode());
        }
        if (throwable instanceof HttpResponseException httpResponseException
                && httpResponseException.getResponse() != null) {
            return classifyStatusCode(httpResponseException.getResponse().getStatusCode());
        }
        if (throwable instanceof SocketTimeoutException
                || throwable instanceof ConnectTimeoutException
                || throwable instanceof ConnectionRequestTimeoutException
                || throwable instanceof TimeoutException) {
            return CdkMeters.OUTCOME_TIMEOUT;
        }
        return null;
    }

    private static String classifyHttpStatus(final HttpStatusCode statusCode) {
        if (statusCode.is4xxClientError()) {
            return CdkMeters.OUTCOME_CLIENT_ERROR;
        }
        if (statusCode.is5xxServerError()) {
            return CdkMeters.OUTCOME_SERVER_ERROR;
        }
        return null;
    }

    private static String classifyStatusCode(final int statusCode) {
        if (statusCode >= 400 && statusCode < 500) {
            return CdkMeters.OUTCOME_CLIENT_ERROR;
        }
        if (statusCode >= 500 && statusCode < 600) {
            return CdkMeters.OUTCOME_SERVER_ERROR;
        }
        return null;
    }
}
