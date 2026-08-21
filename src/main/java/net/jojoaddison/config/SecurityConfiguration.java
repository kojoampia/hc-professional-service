package net.jojoaddison.config;

import static org.springframework.security.config.Customizer.withDefaults;

import net.jojoaddison.security.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import tech.jhipster.config.JHipsterProperties;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfiguration {

    private final JHipsterProperties jHipsterProperties;

    public SecurityConfiguration(JHipsterProperties jHipsterProperties) {
        this.jHipsterProperties = jHipsterProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(
                authz ->
                    // prettier-ignore
                authz
                    .requestMatchers(HttpMethod.POST, "/api/authenticate").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/authenticate").permitAll()
                    .requestMatchers("/api/admin/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    // Applicants hold only ROLE_USER before authority assignment; onboarding
                    // endpoints stay open to authenticated users, with admin-only decisions
                    // enforced via method security on OnboardingResource.
                    .requestMatchers("/api/onboarding/**").authenticated()
                    // Messaging is correspondence, not clinical data. Under the POST /api/** rule
                    // below, carer/angel/chemist/technician could receive a message and never
                    // answer one; this is the same exception onboarding already makes.
                    .requestMatchers("/api/messaging/**").authenticated()
                    // Registering a device for push is not a clinical mutation. This MUST sit
                    // above the POST /api/** rule below: otherwise a carer, angel, chemist or
                    // technician — every read-only role — gets a silent 403 registering a device
                    // and simply never receives notifications, with nothing to point at.
                    .requestMatchers("/api/notifications/**").authenticated()
                    // Booking leave is not a clinical mutation either. Fourth exception of the same
                    // shape, and the one with the plainest consequence: under the POST /api/** rule
                    // below, a carer, care angel, chemist or technician could not ASK FOR TIME OFF.
                    // Per-record authorization is AbsenceService's — you write your own, an
                    // administrator writes anyone's — and approval is @PreAuthorize(ADMIN) on the
                    // resource, so nothing here loosens who may grant.
                    .requestMatchers("/api/absences/**").authenticated()
                    // The STOMP handshake carries no Authorization header (browsers cannot set one
                    // on a WebSocket upgrade). It is authenticated on the CONNECT frame instead —
                    // see WebsocketConfiguration — so the handshake itself is left open.
                    .requestMatchers("/websocket/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/**").hasAnyAuthority(AuthoritiesConstants.CLINICAL_MUTATION)
                    .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyAuthority(AuthoritiesConstants.CLINICAL_MUTATION)
                    .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyAuthority(AuthoritiesConstants.CLINICAL_MUTATION)
                    .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyAuthority(AuthoritiesConstants.CLINICAL_MUTATION)
                    .requestMatchers("/api/**").authenticated()
                    .requestMatchers("/v3/api-docs/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .requestMatchers("/management/health").permitAll()
                    .requestMatchers("/management/health/**").permitAll()
                    .requestMatchers("/management/info").permitAll()
                    .requestMatchers("/management/prometheus").permitAll()
                    .requestMatchers("/management/**").hasAuthority(AuthoritiesConstants.ADMIN)
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(
                exceptions ->
                    exceptions
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler())
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()));
        return http.build();
    }
}
