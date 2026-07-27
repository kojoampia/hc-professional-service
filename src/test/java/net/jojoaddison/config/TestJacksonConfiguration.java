package net.jojoaddison.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 4 auto-configures Jackson 3 ({@code tools.jackson}), so no
 * Jackson 2 {@code ObjectMapper} bean exists anymore — but the generated
 * {@code *ResourceIT} classes still inject one to serialize request bodies.
 * This test-only bean keeps them compiling and running until the ITs are
 * migrated to Jackson 3. Dates are written as ISO-8601 strings to match what
 * the Jackson 3 server side expects.
 */
@TestConfiguration
public class TestJacksonConfiguration {

    @Bean
    public ObjectMapper testRequestObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
