package uk.gov.hmcts.cp.cdk.batch.clients.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApimAuthHeaderService tests")
class ApimAuthHeaderServiceTest {

    @Mock
    private AzureTokenService azureTokenService;

    private ApimAuthHeaderService apimAuthHeaderService;

    @BeforeEach
    void setUp() {
        apimAuthHeaderService = new ApimAuthHeaderService(azureTokenService);
    }

    private RagClientProperties baseProps() {
        RagClientProperties props = new RagClientProperties();
        props.setBaseUrl("https://example.test");
        props.setConnectTimeoutMs(1000);
        props.setReadTimeoutMs(5000);
        props.setHeaders(new HashMap<>());
        props.setAuth(new RagClientProperties.Auth());
        return props;
    }

    @Nested
    @DisplayName("applyCommonHeaders")
    class ApplyCommonHeaders {

        @Test
        @DisplayName("No-op when headers is null")
        void noopWhenHeadersNull() {
            HttpHeaders httpHeaders = new HttpHeaders();
            apimAuthHeaderService.applyCommonHeaders(httpHeaders, null);
            assertThat(httpHeaders.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Adds provided headers")
        void addsProvidedHeaders() {
            HttpHeaders httpHeaders = new HttpHeaders();
            Map<String, String> incoming = Map.of(
                    "Accept", "application/json",
                    "X-Trace", "abc"
            );

            apimAuthHeaderService.applyCommonHeaders(httpHeaders, incoming);

            assertThat(httpHeaders.getFirst("Accept")).isEqualTo("application/json");
            assertThat(httpHeaders.getFirst("X-Trace")).isEqualTo("abc");
        }
    }

    @Nested
    @DisplayName("applyAuthHeaders - subscription-key mode")
    class ApplyAuthHeadersSubscriptionKey {

        @Test
        @DisplayName("Uses auth.subscriptionKey when present")
        void usesAuthSubscriptionKey() {
            RagClientProperties props = baseProps();
            props.getAuth().setMode("subscription-key");
            props.getAuth().setSubscriptionKey("sub-key-123");

            HttpHeaders httpHeaders = new HttpHeaders();
            apimAuthHeaderService.applyAuthHeaders(httpHeaders, props);

            assertThat(httpHeaders.getFirst("Ocp-Apim-Subscription-Key")).isEqualTo("sub-key-123");
            verifyNoMoreInteractions(azureTokenService);
        }

        @Test
        @DisplayName("Falls back to headers['Ocp-Apim-Subscription-Key'] when auth.subscriptionKey missing")
        void fallsBackToHeaders() {
            RagClientProperties props = baseProps();
            props.getAuth().setMode("subscription-key");
            props.getHeaders().put("Ocp-Apim-Subscription-Key", "fallback-key");

            HttpHeaders httpHeaders = new HttpHeaders();
            apimAuthHeaderService.applyAuthHeaders(httpHeaders, props);

            assertThat(httpHeaders.getFirst("Ocp-Apim-Subscription-Key")).isEqualTo("fallback-key");
            verifyNoMoreInteractions(azureTokenService);
        }

        @Test
        @DisplayName("Throws when no key provided")
        void throwsWhenMissingKey() {
            RagClientProperties props = baseProps();
            props.getAuth().setMode("subscription-key");
            // no key in auth; no key in headers

            HttpHeaders httpHeaders = new HttpHeaders();
            assertThatThrownBy(() -> apimAuthHeaderService.applyAuthHeaders(httpHeaders, props))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Ocp-Apim-Subscription-Key is required");
            verifyNoMoreInteractions(azureTokenService);
        }
    }

    @Nested
    @DisplayName("applyAuthHeaders - AAD mode")
    class ApplyAuthHeadersAad {

        @Test
        @DisplayName("Adds Authorization: Bearer <token> using provided scope")
        void addsBearerToken() {
            RagClientProperties props = baseProps();
            props.getAuth().setMode("aad");
            RagClientProperties.Auth.Aad aad = new RagClientProperties.Auth.Aad();
            aad.setScope("api://00000000-0000-0000-0000-000000000000/.default");
            props.getAuth().setAad(aad);

            when(azureTokenService.getAccessToken("api://00000000-0000-0000-0000-000000000000/.default"))
                    .thenReturn("access.token.value");

            HttpHeaders httpHeaders = new HttpHeaders();
            apimAuthHeaderService.applyAuthHeaders(httpHeaders, props);

            assertThat(httpHeaders.getFirst("Authorization")).isEqualTo("Bearer access.token.value");
        }

        @Test
        @DisplayName("Throws when scope missing")
        void throwsWhenScopeMissing() {
            RagClientProperties props = baseProps();
            props.getAuth().setMode("aad");
            // no scope set

            HttpHeaders httpHeaders = new HttpHeaders();
            assertThatThrownBy(() -> apimAuthHeaderService.applyAuthHeaders(httpHeaders, props))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("rag.client.auth.aad.scope is required");
        }
    }

    @Test
    @DisplayName("Auth.setMode rejects unsupported modes early")
    void setModeRejectsUnsupported() {
        RagClientProperties.Auth auth = new RagClientProperties.Auth();
        assertThatThrownBy(() -> auth.setMode("kerberos"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported rag.client.auth.mode");
    }
}
