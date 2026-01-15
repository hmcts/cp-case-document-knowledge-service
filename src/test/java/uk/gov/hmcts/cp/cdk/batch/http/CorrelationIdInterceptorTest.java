package uk.gov.hmcts.cp.cdk.batch.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.http.CorrelationIdInterceptor.HEADER;

import uk.gov.hmcts.cp.cdk.http.CorrelationIdInterceptor;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

@ExtendWith(MockitoExtension.class)
class CorrelationIdInterceptorTest {

    private final CorrelationIdInterceptor interceptor = new CorrelationIdInterceptor();
    @Mock
    private HttpRequest request;
    @Mock
    private ClientHttpRequestExecution execution;
    @Mock
    private ClientHttpResponse response;
    @Mock
    private HttpHeaders headers;

    @Test
    void executesRequestAndReturnsResponse_uniqueCorrelationIdAdded_whenNoCorrelationInTheRequestHeader() throws IOException {
        byte[] body = "test".getBytes();

        // Mock execution to return our response
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getFirst(HEADER)).thenReturn(null);
        when(execution.execute(request, body)).thenReturn(response);

        // Execute interceptor
        ClientHttpResponse result = interceptor.intercept(request, body, execution);

        // Verify the execution was called
        verify(execution, times(1)).execute(request, body);

        // The returned response should be the same
        assertEquals(response, result);
        verify(headers).add(eq(HEADER), anyString());
    }
}