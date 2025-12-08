package uk.gov.hmcts.cp.cdk.batch.clients.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


@ExtendWith(MockitoExtension.class)
class AzureTokenServiceTest {

    @Mock
    private TokenCredential tokenCredential;

    private AzureTokenService service;

    @BeforeEach
    void setup() {
        service = new AzureTokenService(tokenCredential);
    }

    @Test
    void shouldReturnAccessToken_whenScopeIsValid() {
        // given
        final AccessToken token = new AccessToken("abc", OffsetDateTime.now().plusHours(1));
        when(tokenCredential.getTokenSync(any())).thenReturn(token);

        // when
        final String result = service.getAccessToken("test-scope");

        // then
        assertThat(result).isEqualTo("abc");
        verify(tokenCredential).getTokenSync(any(TokenRequestContext.class));
    }

    @Test
    void shouldThrowIllegalArgumentException_whenScopeNullOrBlank() {
        assertThrows(IllegalArgumentException.class, () -> service.getAccessToken(null));
        assertThrows(IllegalArgumentException.class, () -> service.getAccessToken(""));
        assertThrows(IllegalArgumentException.class, () -> service.getAccessToken("   "));
        verifyNoInteractions(tokenCredential);
    }

    @Test
    void shouldWrapExceptionIntoIllegalStateException() {
        // given
        when(tokenCredential.getTokenSync(any())).thenThrow(new RuntimeException("boom"));

        // when / then
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.getAccessToken("scope")
        );

        assertThat(ex.getMessage().contains("Failed to acquire AAD access token")).isTrue();
    }
}