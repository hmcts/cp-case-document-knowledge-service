package uk.gov.hmcts.cp.cdk.clients.common;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AzureTokenService {

    private final TokenCredential tokenCredential;

    public String getAccessToken(final String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope must not be blank");
        }
        try {
            final TokenRequestContext requestContext = new TokenRequestContext().addScopes(scope);
            final AccessToken accessToken = tokenCredential.getTokenSync(requestContext);
            log.debug("Issued AAD access token for scope='{}', expiresAt={}", scope, accessToken.getExpiresAt());
            return accessToken.getToken();
        } catch (final RuntimeException exception) {
            log.error("Failed to acquire AAD access token for scope='{}'", scope, exception);
            throw new IllegalStateException("Failed to acquire AAD access token", exception);
        }
    }
}
