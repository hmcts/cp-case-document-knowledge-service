package uk.gov.hmcts.cp.cdk.filters.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Global configuration to ensure Jackson's ObjectMapper correctly handles 
 * Java 8 Date/Time types (like ZonedDateTime, Instant, and LocalDate).
 * * This prevents the "Java 8 date/time type not supported by default" error
 * in framework-managed components (like filters and services).
 */
@Configuration
public class JacksonConfig {

    /**
     * Creates and registers the globally managed ObjectMapper bean.
     * Spring Boot will use this method to customize the default ObjectMapper.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 1. Register the JavaTimeModule (the essential fix for your error)
        mapper.registerModule(new JavaTimeModule());

        // 2. Register the Jdk8Module (Fixes java.util.Optional, Stream, etc.)
        mapper.registerModule(new Jdk8Module());

        return mapper;
    }
}