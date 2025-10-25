package uk.gov.hmcts.cp.cdk.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(CourtDocumentSearchApiProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private static final long DEFAULT_MAX_AGE_SECONDS = 3600L;

    @Value("${app.cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Override
    public void addCorsMappings(final CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods(
                        HttpMethod.GET.name(),
                        HttpMethod.POST.name(),
                        HttpMethod.PUT.name(),
                        HttpMethod.PATCH.name(),
                        HttpMethod.DELETE.name(),
                        HttpMethod.OPTIONS.name()
                )
                .allowedHeaders("*")
                .exposedHeaders("Location", "X-Total-Count")
                .allowCredentials(true)
                .maxAge(DEFAULT_MAX_AGE_SECONDS);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
