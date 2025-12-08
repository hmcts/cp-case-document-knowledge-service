package uk.gov.hmcts.cp.cdk.batch.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

@ExtendWith(MockitoExtension.class)
class DebugLoggingInterceptorTest {

    @Mock
    private Logger logger;
    @Mock
    private HttpRequest request;
    @Mock
    private ClientHttpRequestExecution execution;
    @Mock
    private ClientHttpResponse response;

    private DebugLoggingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new DebugLoggingInterceptor();
    }

    @Test
    void testIntercept_executesRequestAndReturnsResponse() throws IOException {
        byte[] body = "test".getBytes();

        // Mock execution to return our response
        when(execution.execute(request, body)).thenReturn(response);

        // Execute interceptor
        ClientHttpResponse result = interceptor.intercept(request, body, execution);

        // Verify the execution was called
        verify(execution, times(1)).execute(request, body);

        // The returned response should be the same
        assertEquals(response, result);
    }

}