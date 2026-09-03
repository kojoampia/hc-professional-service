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
                    // The estate-wide recipient directory: account id, LOGIN and role for every
                    // ACTIVE professional, unpaginated. The login is what /api/authenticate takes,
                    // so an unauthorised read of this is the estate's valid-login list. All ten
                    // rather than CLINICAL_MUTATION's six, because it is a read and because the
                    // mobile recipient picker is what a carer composes from.
                    .requestMatchers(HttpMethod.GET, "/api/messaging/recipients").hasAnyAuthority(AuthoritiesConstants.CLINICAL_AND_ADMIN)
                    // Starting a thread writes into OTHER PEOPLE'S inboxes — including a
                    // recipientRole broadcast to every nurse or doctor, with a push notification
                    // behind it. Exact path, so a reply into a thread the caller is already a member
                    // of (/conversations/{id}/messages) is unaffected: that one is own-scoped, and
                    // MessagingService.reply refuses a non-member with a 404 whatever this says.
                    .requestMatchers(HttpMethod.POST, "/api/messaging/conversations").hasAnyAuthority(AuthoritiesConstants.CLINICAL_AND_ADMIN)
                    // Everything else under messaging is correspondence, not clinical data, and is
                    // scoped to the caller's own MessageRecipient rows. Under the POST /api/** rule
                    // below, carer/angel/chemist/technician could receive a message and never answer
                    // one; this is the same exception onboarding already makes.
                    //
                    // THE TWO RULES ABOVE ARE THE SECOND LAYER, and they answer a different question
                    // from the gateway's. The gateway decides who reaches this service; this decides
                    // who may act, and it must hold on its own — the three gateways share one
                    // signing key and this service validates no issuer, so a sibling stack's token
                    // arrives here indistinguishable from one of ours. Held at .authenticated()
                    // across the whole prefix, the mutation matrix below never applied to messaging
                    // at all and the service caught nothing the gateway let through. An applicant is
                    // deliberately not a correspondent: nothing in this domain addresses one, since
                    // MessagingService.recipients and resolveRole both read ACTIVE applications
                    // only, and onboarding correspondence travels as correctionNotes on the
                    // application. See ClinicalAuthorityMatrixIT and docs/backlog.md item 19.
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
