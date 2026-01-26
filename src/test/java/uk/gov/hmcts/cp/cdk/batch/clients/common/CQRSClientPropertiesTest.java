package uk.gov.hmcts.cp.cdk.batch.clients.common;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class CQRSClientPropertiesTest {

    @Test
    void bindsYamlLikePropertiesIntoTypedBean() {
        var source = new MapConfigurationPropertySource(Map.of(
                "cqrs.client.base-url", "http://localhost:9999",
                "cqrs.client.connect-timeout-ms", "5000",
                "cqrs.client.read-timeout-ms", "20000",
                "cqrs.client.headers.cjs-cppuid", "X-CJSCPPUID"
        ));

        var binder = new Binder(source);
        var props = binder.bind("cqrs.client", CQRSClientProperties.class).get();

        assertThat(props.baseUrl()).isEqualTo("http://localhost:9999");
        assertThat(props.connectTimeoutMs()).isEqualTo(5000);
        assertThat(props.readTimeoutMs()).isEqualTo(20000);
        assertThat(props.headers().cjsCppuid()).isEqualTo("X-CJSCPPUID");
        assertThat(props.connectTimeout()).hasMillis(5000);
        assertThat(props.readTimeout()).hasMillis(20000);
    }

    @Test
    void defaultsHeadersWhenMissing() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "cqrs.client.base-url", "http://x"
        )));
        var props = binder.bind("cqrs.client", CQRSClientProperties.class).get();
        assertThat(props.headers().cjsCppuid()).isEqualTo("CJSCPPUID");
    }
}
