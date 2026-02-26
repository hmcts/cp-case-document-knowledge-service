package uk.gov.hmcts.cp.cdk.util;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

public final class UtilHttp {
    private UtilHttp() {
    }

    public static RestTemplate newClient() {
        final RestTemplate rt = new RestTemplate(
                new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
        );

        rt.getInterceptors().add((request, body, execution) -> {
           final HttpHeaders headers = request.getHeaders();
           final List<String> existing = headers.get(UtilConstants.CJSCPPUID);
            if (existing == null || existing.isEmpty()) {
                headers.add(UtilConstants.CJSCPPUID, UtilConstants.USER_WITH_PERMISSIONS);
            }
            return execution.execute(request, body);
        });

        return rt;
    }
}
