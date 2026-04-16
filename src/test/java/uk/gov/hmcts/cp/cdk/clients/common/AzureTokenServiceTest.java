package uk.gov.hmcts.cp.cdk.clients.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AzureTokenServiceTest {

    @Mock
    private TokenCredential tokenCredential;
    @InjectMocks
    private AzureTokenService azureTokenService;

    @Captor
    private ArgumentCaptor<TokenRequestContext> tokenRequestCaptor;

    @Test
    void shouldReturnAccessToken_whenScopeIsValid() {
        // Arrange
        final String scope = "https://example/.default";
        final String expectedToken = "fake-token";
        final OffsetDateTime expiry = OffsetDateTime.now().plusHours(1);

        final AccessToken accessToken = new AccessToken(expectedToken, expiry);
        when(tokenCredential.getTokenSync(any(TokenRequestContext.class))).thenReturn(accessToken);

        final String result = azureTokenService.getAccessToken(scope);

        assertEquals(expectedToken, result);

        verify(tokenCredential).getTokenSync(tokenRequestCaptor.capture());

        assertTrue(tokenRequestCaptor.getValue().getScopes().contains(scope));
    }

    @Test
    void shouldThrowException_whenScopeIsNull() {
        assertThrows(IllegalArgumentException.class, () -> azureTokenService.getAccessToken(null));
    }

    @Test
    void shouldThrowException_whenScopeIsBlank() {
        assertThrows(IllegalArgumentException.class, () -> azureTokenService.getAccessToken("   "));
    }

    @Test
    void shouldWrapException_whenTokenAcquisitionFails() {
        final String scope = "https://example/.default";
        when(tokenCredential.getTokenSync(any(TokenRequestContext.class))).thenThrow(new RuntimeException("AAD failure"));

        final IllegalStateException exception = assertThrows(IllegalStateException.class, () -> azureTokenService.getAccessToken(scope));

        assertThat(exception.getMessage()).isEqualTo("Failed to acquire AAD access token");
        assertThat(exception.getCause()).isNotNull();
        assertThat(exception.getCause().getMessage()).isEqualTo("AAD failure");
    }
}