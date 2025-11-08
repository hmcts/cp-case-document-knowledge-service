package uk.gov.hmcts.cp.cdk.testsupport;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

public final class TestHttp {
    private TestHttp() {}

    public static RestTemplate newClient() {
        RestTemplate rt = new RestTemplate(
                new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
        );

        rt.getInterceptors().add((request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            List<String> existing = headers.get(TestConstants.HEADER_NAME);
            if (existing == null || existing.isEmpty()) {
                headers.add(TestConstants.HEADER_NAME, TestConstants.HEADER_VALUE);
            }
            return execution.execute(request, body);
        });

        return rt;
    }
}
