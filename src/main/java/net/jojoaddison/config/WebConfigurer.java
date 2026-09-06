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
     * <p><b>This bean is necessary and not sufficient, and the first attempt shipped only this
     * half.</b> Spring Cloud Gateway <b>5.0.2</b> — what {@code gateway/} resolves through
     * {@code spring-cloud 2025.1.2}, verified from the jar inside the running image — gates
     * {@code XForwardedHeadersFilter} on {@code TrustedProxies$XForwardedTrustedProxiesCondition},
     * which requires {@code spring.cloud.gateway.server.webflux.trusted-proxies} to be set and
     * non-blank. Worse, the same version registers {@code RemoveXForwardedHeadersFilter} on the
     * inverse condition, which strips every {@code x-forwarded-*} header from the relayed request.
     * So an unconfigured gateway does not merely omit {@code X-Forwarded-Prefix} — it deletes it,
     * and {@code ForwardedHeaderFilter.shouldNotFilter} then short-circuits on every real request.
     * The property is set nowhere by default and the short form
     * {@code spring.cloud.gateway.trusted-proxies} binds silently without taking effect, which is a
     * second way to arrive at the same inert result.
     *
     * <p><b>Both halves are now in place</b> (2026-09-05): {@code trusted-proxies},
     * {@code x-forwarded.port-enabled=false} and {@code prefix-append=false} are set on the gateway
     * in {@code deploy/prod-server/compose.yml}, {@code deploy/docker-compose.yml} and
     * {@code quality/compose.yml}, and all three nginx files scrub the inbound header (below). This
     * paragraph said the gateway side was "NOT DONE" until that landed; do not restore that reading
     * without checking those five files.
     *
     * <p>What remains true is the warning about evidence. Do not read a green
     * {@code PaginationLinkHeaderIT} — or {@code LocationHeaderIT} — as proof the deployed headers
     * are right: both set the forwarded headers by hand, so they exercise this filter rather than
     * the deployment. Neither header has yet been observed on a running system; that is
     * {@code docs/backlog.md} item 42, and it is the only check that distinguishes this fix from the
     * no-op that preceded it.
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
     * <p><b>Trust boundary — the reason the two halves had to land together.</b> This filter
     * believes whatever is in front of it, so the header must not be settable by the caller. While
     * the gateway was unconfigured its {@code RemoveXForwardedHeadersFilter} stripped the header,
     * and that was the only reason a client-supplied value could not reach here — a protection that
     * disappears the instant {@code trusted-proxies} is set to make this bean do its job. So the
     * edge scrub and the gateway setting are one change, and <b>three</b> nginx files needed it,
     * not one: {@code deploy/docker/proxy-headers.inc}, {@code deploy/prod-server/hc-professional-app.conf}
     * (the internet-facing hop) and {@code quality/host-site.conf}, whose {@code location /} block
     * had the scrub from the start and whose {@code location /websocket/} sibling did not. All three
     * now blank {@code X-Forwarded-Prefix} and {@code X-Forwarded-Port}; nginx passes unrecognised
     * client headers straight through, so a missing line is a silently open door rather than an
     * error.
     *
     * <p><b>A second reader of these headers arrived with item 41.</b>
     * {@link net.jojoaddison.web.rest.util.LocationUri} builds the {@code Location} of every
     * {@code 201} from the same forwarded values, and {@code ExceptionTranslator.extractURI}
     * reflects the prefixed path into problem-detail bodies. No new <i>header</i> is exposed — a
     * forged prefix could already steer the {@code Link} — but the scrub now underwrites three
     * things rather than one.
     *
     * <p><b>{@code X-Forwarded-Host} is a separate question and is not scrubbed.</b> Both nginx hops
     * set it from {@code $http_host}, so the host in every absolute URL this service emits is the
     * {@code Host} the client sent — bounded by which {@code Host} values the vhost accepts, which
     * is decided by a {@code server_name} in {@code /etc/nginx}, a file this workspace does not own
     * and cannot read from here. That is pre-existing from item 31 rather than new, and it is worth
     * stating rather than leaving inside a sentence about the prefix: if a wildcard vhost ever
     * answers, the {@code Location} of a create becomes attacker-choosable in its authority half.
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
