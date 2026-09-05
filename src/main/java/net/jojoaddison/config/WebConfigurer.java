package net.jojoaddison.config;

import jakarta.servlet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.CollectionUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;
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
     * Makes this service see the request the way the caller made it, rather than the way the gateway
     * relayed it (backlog.md item 31).
     *
     * <p><b>The defect this closes.</b> Every paginated read here builds its {@code Link} header from
     * {@code ServletUriComponentsBuilder.fromCurrentRequest()}, and the gateway routes
     * {@code /services/professionalservice/**} with {@code StripPrefix=2}. So the service saw
     * {@code /api/patients} and advertised {@code Link: <http://professional.abofonsa.local/api/patients?page=1&size=20>;
     * rel="next"} — a URL that 404s when a client follows it, because the prefix the gateway removed
     * is not put back. Five endpoints emitted it: {@code GET /api/patients},
     * {@code /api/patients/&#123;id&#125;/cases}, {@code /api/cases}, {@code /api/profiles} and
     * {@code /api/duty-roster/all}. Not one of them was wrong in its own code — they are all wrong in
     * the same way, which is why the fix belongs here and not in five call sites.
     *
     * <p><b>⚠ THIS IS INERT UNTIL THE GATEWAY IS CONFIGURED, AND THAT IS NOT DONE.</b> The half of
     * the contract that sends the header does not currently run. Spring Cloud Gateway <b>5.0.2</b> —
     * what {@code gateway/} resolves through {@code spring-cloud 2025.1.2}, verified from the jar
     * inside the running image — gates {@code XForwardedHeadersFilter} on
     * {@code TrustedProxies$XForwardedTrustedProxiesCondition}, which requires
     * {@code spring.cloud.gateway.trusted-proxies} to be set and non-blank. It is set **nowhere** in
     * this estate. Worse, the same version registers {@code RemoveXForwardedHeadersFilter} on the
     * inverse condition, which strips every {@code x-forwarded-*} header from the relayed request. So
     * the gateway does not merely omit {@code X-Forwarded-Prefix} — it deletes it, and
     * {@code ForwardedHeaderFilter.shouldNotFilter} then short-circuits on every real request.
     *
     * <p>This bean is therefore <b>necessary but not sufficient</b> for backlog item 31, and on its
     * own changes nothing a client can observe. Do not read a green
     * {@code PaginationLinkHeaderIT} as evidence the deployed {@code Link} is fixed: that test sets
     * the three forwarded headers by hand, so it exercises this filter rather than the deployment.
     * Closing item 31 needs {@code trusted-proxies} on the gateway <i>and</i> the nginx scrub in
     * §"Trust boundary" below, in the same change — see {@code docs/backlog.md} item 31.
     *
     * <p><b>Why the framework filter and not a hardcoded prefix.</b> The prefix is not this service's
     * to know: it is whatever the component in front stripped, and that component says so in
     * {@code X-Forwarded-Prefix}. {@code ForwardedHeaderFilter} is the servlet-side half of that
     * contract, and the nginx configuration in {@code deploy/docker/proxy-headers.inc} already names
     * it by name. A direct call carries none of those headers, the filter is then a no-op, and
     * {@code fromCurrentRequest()} keeps answering {@code /api/patients} — which is correct, because
     * on a direct call that is the URL.
     *
     * <p><b>It fixes the scheme too, and that only shows in production.</b> On quality the edge is
     * plain HTTP so the emitted {@code Link} looked right apart from the path; on
     * {@code professional.abofonsa.com} TLS terminates at nginx, so without this every absolute URL
     * this service produced claimed {@code http://} for an origin that is HTTPS-only.
     *
     * <p><b>Registered as a bean rather than via {@code server.forward-headers-strategy=framework}.</b>
     * The property does the same thing, but this stack is configured almost entirely by environment
     * variables in compose, and a property can be switched off from there by accident — silently, with
     * the only symptom being a header nobody reads until they do. A bean cannot.
     *
     * <p><b>Trust boundary — the reason the two halves must land together.</b> This filter believes
     * whatever is in front of it, so the header must not be settable by the caller. Today the
     * gateway's {@code RemoveXForwardedHeadersFilter} strips it, which is the only reason a
     * client-supplied value cannot reach here — a protection that disappears the instant
     * {@code trusted-proxies} is set to make this bean do its job. At that point the edge must scrub
     * it, and <b>three</b> files need it, not one:
     *
     * <ul>
     *   <li>{@code quality/host-site.conf:126} — already does, in {@code location /} only; its
     *       {@code location /websocket/} block does not.</li>
     *   <li>{@code deploy/docker/proxy-headers.inc} — does not. It sets {@code Host},
     *       {@code X-Real-IP}, {@code X-Forwarded-For}, {@code -Host} and {@code -Proto}, and names
     *       this filter in a comment, but never blanks the prefix.</li>
     *   <li>{@code deploy/prod-server/hc-professional-app.conf} — the internet-facing hop, and does
     *       not either. nginx passes unrecognised client headers straight through.</li>
     * </ul>
     *
     * <p>The blast radius is wider than the prefix, which is worth stating because it is easy to scope
     * this to {@code Link}: the same filter makes {@code getRemoteAddr()} return the first entry of
     * {@code X-Forwarded-For}, and both nginx hops use {@code $proxy_add_x_forwarded_for}, which
     * <i>appends</i> — so that first entry is whatever the client sent. Any future IP-based logging,
     * rate limiting or allow-listing in this service would be spoofable from the day it is written.
     *
     * <p>An authentication bypass is <i>not</i> reachable, and the reason is worth recording so nobody
     * has to re-derive it: this filter overrides {@code getRequestURI()} and {@code getContextPath()}
     * but not {@code getServletPath()}/{@code getPathInfo()}, and both Spring Security and
     * {@code DispatcherServlet} match on {@code pathWithinApplication} — which is
     * {@code prefix + path} minus {@code prefix}, invariant under any prefix value. A prefix that
     * moved the security match would move the handler mapping identically, giving a 404 rather than
     * an unguarded 200.
     *
     * <p>Ordered ahead of everything, including {@link #conditionalGetFilter()} and the security
     * chain: a filter that rewrites the request URI has to run before anything that reads it.
     */
    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        registration.setName("forwardedHeaderFilter");
        // REQUEST, ASYNC and ERROR — the same three Boot's own ForwardedHeaderFilterConfiguration
        // registers, and not the REQUEST-only default. ForwardedHeaderFilter deliberately overrides
        // shouldNotFilterAsyncDispatch() and shouldNotFilterErrorDispatch() to false and carries a
        // getErrorRequestUri() for the error dispatch; leaving the default would mean the /error
        // forward runs unwrapped, so ExceptionTranslator's problem-detail `path` would revert to the
        // unprefixed URI on exactly the responses where a caller is already being told something went
        // wrong. Registering the bean rather than setting server.forward-headers-strategy is only
        // worth doing if it is not the weaker registration of the two.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
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
