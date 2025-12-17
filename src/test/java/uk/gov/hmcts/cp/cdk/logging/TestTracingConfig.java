package uk.gov.hmcts.cp.cdk.logging;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class TestTracingConfig {

    @Value("${spring.application.name:app}")
    private String applicationName;

    @Bean
    public HandlerInterceptor tracingInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request,
                                     HttpServletResponse response,
                                     Object handler) {
                String traceId = Optional.ofNullable(request.getHeader("traceId"))
                        .filter(s -> !s.isBlank())
                        .orElse(UUID.randomUUID().toString());
                String spanId = Optional.ofNullable(request.getHeader("spanId"))
                        .filter(s -> !s.isBlank())
                        .orElse(UUID.randomUUID().toString());

                MDC.put("traceId", traceId);
                MDC.put("spanId", spanId);
                MDC.put("applicationName", applicationName);

                response.setHeader("traceId", traceId);
                response.setHeader("spanId", spanId);
                return true;
            }
        };
    }

    @Bean
    public WebMvcConfigurer testMvcConfigurer(HandlerInterceptor tracingInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(tracingInterceptor);
            }
        };
    }

}
