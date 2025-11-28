package uk.gov.hmcts.cp.cdk.testsupport;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

public final class TestHttp {
    private TestHttp() {
    }

    public static RestTemplate newClient() {
        RestTemplate rt = new RestTemplate(
                new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
        );

        rt.getInterceptors().add((request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            List<String> existing = headers.get(TestConstants.CJSCPPUID);
            if (existing == null || existing.isEmpty()) {
                headers.add(TestConstants.CJSCPPUID, TestConstants.USER_WITH_PERMISSIONS);
            }
            return execution.execute(request, body);
        });

        return rt;
    }
}
