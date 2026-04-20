package uk.gov.hmcts.cp.cdk.clients.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApimAuthHeaderServiceTest {

    @Mock
    private AzureTokenService azureTokenService;

    @InjectMocks
    private ApimAuthHeaderService service;

    @Test
    void shouldApplyCommonHeaders_whenHeadersPresent() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        final Map<String, String> headers = new HashMap<>();
        headers.put("X-Test", "value");

        service.applyCommonHeaders(httpHeaders, headers);

        assertThat(httpHeaders.getFirst("X-Test")).isEqualTo("value");
    }

    @Test
    void shouldDoNothing_whenCommonHeadersNull() {
        final HttpHeaders httpHeaders = new HttpHeaders();

        service.applyCommonHeaders(httpHeaders, null);

        assertThat(httpHeaders.isEmpty()).isTrue();
    }

    @Test
    void shouldAddAuthorizationHeader_whenAadMode() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        final RagClientProperties properties = mockAadProperties("scope-123");

        when(azureTokenService.getAccessToken("scope-123")).thenReturn("token-abc");

        service.applyAuthHeaders(httpHeaders, properties);

        assertThat(httpHeaders.getFirst("Authorization")).isEqualTo("Bearer token-abc");
        verify(azureTokenService).getAccessToken("scope-123");
    }

    @Test
    void shouldThrow_whenAadScopeMissing() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        final RagClientProperties properties = mock(RagClientProperties.class);
        final RagClientProperties.Auth auth = mock(RagClientProperties.Auth.class);

        when(properties.getAuth()).thenReturn(auth);
        when(auth.getMode()).thenReturn("aad");
        when(auth.getAad()).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.applyAuthHeaders(httpHeaders, properties));
    }

    @Test
    void shouldAddSubscriptionKeyHeader_whenProvidedDirectly() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        final RagClientProperties properties = mockSubscriptionKeyProperties("my-key", null);

        service.applyAuthHeaders(httpHeaders, properties);

        assertEquals("my-key", httpHeaders.getFirst(ApimAuthHeaderService.OCP_APIM_SUBSCRIPTION_KEY));
    }

    @Test
    void shouldUseFallbackHeader_whenSubscriptionKeyMissing() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        final Map<String, String> fallbackHeaders = new HashMap<>();
        fallbackHeaders.put(ApimAuthHeaderService.OCP_APIM_SUBSCRIPTION_KEY, "fallback-key");

        final RagClientProperties properties = mockSubscriptionKeyProperties(null, fallbackHeaders);

        service.applyAuthHeaders(httpHeaders, properties);

        assertThat(httpHeaders.getFirst(ApimAuthHeaderService.OCP_APIM_SUBSCRIPTION_KEY)).isEqualTo("fallback-key");
    }

    @Test
    void shouldThrow_whenSubscriptionKeyMissing() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        final RagClientProperties properties = mockSubscriptionKeyProperties(null, null);

        assertThrows(IllegalStateException.class, () -> service.applyAuthHeaders(httpHeaders, properties));
    }

    @Test
    void shouldThrow_whenUnsupportedMode() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        final RagClientProperties properties = mock(RagClientProperties.class);
        final RagClientProperties.Auth auth = mock(RagClientProperties.Auth.class);

        when(properties.getAuth()).thenReturn(auth);
        when(auth.getMode()).thenReturn("invalid-mode");

        assertThrows(IllegalArgumentException.class, () -> service.applyAuthHeaders(httpHeaders, properties));
    }

    private RagClientProperties mockAadProperties(String scope) {
        final RagClientProperties properties = mock(RagClientProperties.class);
        final RagClientProperties.Auth auth = mock(RagClientProperties.Auth.class);
        final RagClientProperties.Auth.Aad aad = mock(RagClientProperties.Auth.Aad.class);

        when(properties.getAuth()).thenReturn(auth);
        when(auth.getMode()).thenReturn("aad");
        when(auth.getAad()).thenReturn(aad);
        when(aad.getScope()).thenReturn(scope);

        return properties;
    }

    private RagClientProperties mockSubscriptionKeyProperties(String key, Map<String, String> headers) {
        final RagClientProperties properties = mock(RagClientProperties.class);
        final RagClientProperties.Auth auth = mock(RagClientProperties.Auth.class);

        when(properties.getAuth()).thenReturn(auth);
        when(auth.getMode()).thenReturn("subscription-key");
        when(auth.getSubscriptionKey()).thenReturn(key);
        when(properties.getHeaders()).thenReturn(headers);

        return properties;
    }
}