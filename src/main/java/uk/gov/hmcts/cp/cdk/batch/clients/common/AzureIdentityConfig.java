package uk.gov.hmcts.cp.cdk.batch.clients.common;

import static uk.gov.hmcts.cp.cdk.batch.clients.common.RagClientProperties.AAD;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class AzureIdentityConfig {

    private final RagClientProperties ragClientProperties;

    @Bean
    public TokenCredential tokenCredential() {
        DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();

        final RagClientProperties.Auth auth = ragClientProperties.getAuth();
        if (auth != null && AAD.equalsIgnoreCase(auth.getMode()) && auth.getAad() != null) {
            final String configuredClientId = auth.getAad().getClientId();
            final String configuredTenantId = auth.getAad().getTenantId();

            if (StringUtils.isNotBlank(configuredClientId)) {
                builder = builder.managedIdentityClientId(configuredClientId);
            }
            if (StringUtils.isNotBlank(configuredTenantId)) {
                builder = builder.tenantId(configuredTenantId);
            }
        }
        return builder.build();
    }
}
