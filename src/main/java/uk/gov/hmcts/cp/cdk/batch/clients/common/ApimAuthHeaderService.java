package uk.gov.hmcts.cp.cdk.batch.clients.common;

import static uk.gov.hmcts.cp.cdk.batch.clients.common.RagClientProperties.AAD;
import static uk.gov.hmcts.cp.cdk.batch.clients.common.RagClientProperties.SUBSCRIPTION_KEY;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApimAuthHeaderService {

    public static final String OCP_APIM_SUBSCRIPTION_KEY = "Ocp-Apim-Subscription-Key";

    private final AzureTokenService azureTokenService;

    public void applyCommonHeaders(HttpHeaders httpHeaders, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(httpHeaders::add);
        }
    }

    public void applyAuthHeaders(HttpHeaders httpHeaders, RagClientProperties properties) {
        String mode = Optional.ofNullable(properties.getAuth())
                .map(RagClientProperties.Auth::getMode)
                .orElse(SUBSCRIPTION_KEY)
                .toLowerCase(Locale.ROOT);

        switch (mode) {
            case AAD -> {
                String scope = Optional.ofNullable(properties.getAuth().getAad())
                        .map(RagClientProperties.Auth.Aad::getScope)
                        .orElseThrow(() -> new IllegalStateException("rag.client.auth.aad.scope is required when rag.client.auth.mode=aad"));
                String accessToken = azureTokenService.getAccessToken(scope);
                httpHeaders.add("Authorization", "Bearer " + accessToken);
                log.debug("Added Authorization header via Managed Identity");
            }
            case SUBSCRIPTION_KEY -> {
                String subscriptionKey = Optional.ofNullable(properties.getAuth().getSubscriptionKey())
                        .orElseGet(() -> properties.getHeaders() != null ? properties.getHeaders().get(OCP_APIM_SUBSCRIPTION_KEY) : null);
                if (StringUtils.isBlank(subscriptionKey)) {
                    throw new IllegalStateException("Ocp-Apim-Subscription-Key is required when rag.client.auth.mode=subscription-key");
                }
                httpHeaders.add(OCP_APIM_SUBSCRIPTION_KEY, subscriptionKey);
                log.debug("Added Ocp-Apim-Subscription-Key header");
            }
            default ->
                    throw new IllegalArgumentException("Unsupported rag.client.auth.mode: " + mode);
        }
    }
}
