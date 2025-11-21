package uk.gov.hmcts.cp.cdk.batch.clients.common;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "rag.client")
public class RagClientProperties {

    public static final String SUBSCRIPTION_KEY = "subscription-key";
    public static final String AAD = "aad";
    @NotBlank
    private String baseUrl;

    @Positive
    private int connectTimeoutMs = 3000;

    @Positive
    private int readTimeoutMs = 15_000;

    /**
     * Additional static headers to send with every request.
     * Example: Accept, Content-Type, custom tracing headers, etc.
     */
    private Map<String, String> headers = new HashMap<>();

    /**
     * Authentication configuration. Defaults to subscription-key.
     */
    private Auth auth = new Auth();

    public static class Auth {

        /**
         * Auth mode: "subscription-key" or "aad".
         */
        private String mode = SUBSCRIPTION_KEY;

        /**
         * Used when mode=subscription-key. May be empty if provided via headers["Ocp-Apim-Subscription-Key"].
         */
        private String subscriptionKey;

        /**
         * AAD-specific configuration. Used when mode=aad.
         */
        private Aad aad = new Aad();

        public String getMode() {
            return mode;
        }

        public void setMode(final String mode) {
            if (mode == null || mode.isBlank()) {
                this.mode = SUBSCRIPTION_KEY;
                return;
            }
            final String normalized = mode.trim().toLowerCase();
            if (!SUBSCRIPTION_KEY.equals(normalized) && !AAD.equals(normalized)) {
                throw new IllegalArgumentException("Unsupported rag.client.auth.mode: " + mode);
            }
            this.mode = normalized;
        }


        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(final String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public Aad getAad() {
            return aad;
        }

        public void setAad(final Aad aad) {
            this.aad = aad;
        }

        public static class Aad {

            /**
             * Required when mode=aad. Must be the Application ID URI of the API with '/.default' suffix.
             * Example: api://<guid>/.default or https://your-api.domain/.default
             */
            private String scope;

            /**
             * Optional: explicitly bind to a specific User-Assigned Managed Identity (UAMI).
             * If omitted, DefaultAzureCredential will use AZURE_CLIENT_ID or fall back to its chain.
             */
            private String clientId;

            /**
             * Optional: explicitly set tenant for token acquisition. Usually not required.
             * DefaultAzureCredential can infer this; expose only if you need to force a specific tenant.
             */
            private String tenantId;

            public String getScope() {
                return scope;
            }

            public void setScope(final String scope) {
                this.scope = scope;
            }

            public String getClientId() {
                return clientId;
            }

            public void setClientId(final String clientId) {
                this.clientId = clientId;
            }

            public String getTenantId() {
                return tenantId;
            }

            public void setTenantId(final String tenantId) {
                this.tenantId = tenantId;
            }
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(final int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(final int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(final Map<String, String> headers) {
        this.headers = headers;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(final Auth auth) {
        this.auth = auth;
    }

    public Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs);
    }

    public Duration readTimeout() {
        return Duration.ofMillis(readTimeoutMs);
    }
}
