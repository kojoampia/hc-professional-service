package net.jojoaddison.config;

import jakarta.servlet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.CollectionUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import tech.jhipster.config.JHipsterProperties;

/**
 * Configuration of web application with Servlet 3.0 APIs.
 */
@Configuration
public class WebConfigurer implements ServletContextInitializer {

    private final Logger log = LoggerFactory.getLogger(WebConfigurer.class);

    private final Environment env;

    private final JHipsterProperties jHipsterProperties;

    public WebConfigurer(Environment env, JHipsterProperties jHipsterProperties) {
        this.env = env;
        this.jHipsterProperties = jHipsterProperties;
    }

    @Override
    public void onStartup(ServletContext servletContext) {
        if (env.getActiveProfiles().length != 0) {
            log.info("Web application configuration, using profiles: {}", (Object[]) env.getActiveProfiles());
        }

        log.info("Web application fully configured");
    }

    /**
     * Conditional GET on the clinician read endpoints a phone polls (web-mobile-port.md § Phase 1.2).
     *
     * <p><b>Why a shallow ETag and not a cursor.</b> The patient directory is the union of this
     * service's tasks and patientservice's cases — two stores with no single monotonic clock — so an
     * {@code updatedAfter} cursor would be a correctness claim this service cannot back. An ETag
     * needs no such claim: it hashes the bytes actually produced.
     *
     * <p><b>Be honest about what it buys.</b> A shallow ETag saves <em>bandwidth, not server work</em>
     * — the response is computed in full and then discarded when it matches. That is the right trade
     * for a handset on mobile data polling a roster, and the wrong one if these endpoints ever become
     * hot. If they do, this is the first thing to revisit, not the last.
     *
     * <p><b>{@code /api/duty-roster/day/{date}} is deliberately absent from this list.</b> That
     * endpoint calls {@code refreshSnapshots} — a write on a read path, documented on
     * {@code DutyRosterResource} and intentional. Buffering and hashing it would invite a caller to
     * treat it as cacheable, which it is not.
     *
     * <p>Patterns are servlet patterns, where {@code /api/patients/*} also matches {@code
     * /api/patients} itself. The two duty-roster entries are exact on purpose, so that adding a new
     * sub-path there does not silently inherit caching.
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> conditionalGetFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/patients/*", "/api/dashboard/*", "/api/duty-roster", "/api/duty-roster/summary");
        registration.setName("conditionalGetFilter");
        return registration;
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = jHipsterProperties.getCors();
        if (!CollectionUtils.isEmpty(config.getAllowedOrigins()) || !CollectionUtils.isEmpty(config.getAllowedOriginPatterns())) {
            log.debug("Registering CORS filter");
            source.registerCorsConfiguration("/api/**", config);
            source.registerCorsConfiguration("/management/**", config);
            source.registerCorsConfiguration("/v3/api-docs", config);
            source.registerCorsConfiguration("/swagger-ui/**", config);
        }
        return new CorsFilter(source);
    }
}
