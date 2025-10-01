package uk.gov.hmcts.cp.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

public class ActuatorHttpLiveTest {

    private final String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8082/casedocumentknowledge-service");
    private final RestTemplate http = new RestTemplate();

    @Test
    void health_is_up() {
        ResponseEntity<String> res = http.exchange(
                baseUrl + "/actuator/health", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheus_is_exposed() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(java.util.List.of(MediaType.TEXT_PLAIN));
        ResponseEntity<String> res = http.exchange(
                baseUrl + "/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(h),
                String.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("application_started_time_seconds");
    }
}
