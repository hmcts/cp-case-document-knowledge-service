package uk.gov.hmcts.cp.cdk.batch.clients.common;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import com.azure.core.credential.TokenCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


@ExtendWith(MockitoExtension.class)
class AzureIdentityConfigTest {

    @Mock
    private RagClientProperties ragClientProperties;

    @Mock
    private RagClientProperties.Auth auth;

    @Mock
    private RagClientProperties.Auth.Aad aad;

    private AzureIdentityConfig config;

    @BeforeEach
    void setup() {
        config = new AzureIdentityConfig(ragClientProperties);
    }

    @Test
    void shouldBuildDefaultCredential_whenAuthIsNull() {
        when(ragClientProperties.getAuth()).thenReturn(null);

        final TokenCredential credential = config.tokenCredential();

        assertNotNull(credential);
    }

    @Test
    void shouldConfigureClientIdAndTenant_whenBothProvided() {
        // arrange
        when(ragClientProperties.getAuth()).thenReturn(auth);
        when(auth.getMode()).thenReturn("AAD");
        when(auth.getAad()).thenReturn(aad);
        when(aad.getClientId()).thenReturn("client-id");
        when(aad.getTenantId()).thenReturn("tenant-id");

        // act
        final TokenCredential credential = config.tokenCredential();

        // assert — the real build returns DefaultAzureCredential
        assertNotNull(credential);
    }

    @Test
    void shouldIgnoreBlankValues() {
        // arrange
        when(ragClientProperties.getAuth()).thenReturn(auth);
        when(auth.getMode()).thenReturn("AAD");
        when(auth.getAad()).thenReturn(aad);
        when(aad.getClientId()).thenReturn(" ");
        when(aad.getTenantId()).thenReturn("");

        // act
        final TokenCredential credential = config.tokenCredential();

        assertNotNull(credential);
    }

}