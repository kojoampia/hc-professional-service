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
                    // THE CALLER'S OWN PROFILE, and the same island as onboarding above because it is
                    // the same caller: profile.md's step 2 is written by an applicant holding
                    // ROLE_USER and nothing else, so it cannot be gated on a clinical role. See
                    // OwnProfileResource, which carries the argument.
                    //
                    // IT MUST SIT ABOVE THE METHOD RULES BELOW. Without this line the PUT falls
                    // through to `PUT /api/** -> CLINICAL_MUTATION` and EVERY APPLICANT IS 403'd ON
                    // THEIR OWN PROFILE — the one state the endpoint exists to serve. The GET would
                    // survive on `/api/** -> .authenticated()`, which is the trap: a half-working
                    // endpoint reads as "the write is broken" rather than "the rule is missing".
                    //
                    // /api/profile IS NOT /api/profiles, and this is the line that depends on it. The
                    // admin read gate below names the literal "/api/profiles" plus
                    // "/api/profiles/**"; neither pattern matches the singular path, so it inherits
                    // the catch-all and not the gate — and, read the other way, this matcher cannot
                    // widen the plural surface. ClinicalAuthorityMatrixIT asserts BOTH halves,
                    // because that is a claim about Spring's pattern matching and not about this
                    // comment.
                    //
                    // METHOD-AGNOSTIC, SO HEAD IS COVERED. Spring MVC dispatches a HEAD to the
                    // @GetMapping handler, and a rule scoped to HttpMethod.GET lets it fall to
                    // whatever sits below — a measured fail-open on /api/profiles, caught in review
                    // (item 143). This path names no subject, so it is not an existence oracle about
                    // anyone; what a method-scoped rule would still do is answer the same request
                    // under a different authority depending on the verb. Add a verb here, never an
                    // exception below.
                    //
                    // The handler carries a matching @PreAuthorize("isAuthenticated()"). Two layers,
                    // as api/ PR #55 settled, and neither is deletable on the strength of the other.
                    .requestMatchers("/api/profile").authenticated()
                    // AND THE SAME ISLAND CARRIES THE CALLER'S OWN DOCUMENTS, for the same caller and
                    // by the same reasoning extended rather than re-argued (profile.md step 3, T2).
                    // OwnPersonalDocumentResource takes no subject on either of its collection
                    // mappings: profileId is derived from the caller's own profile through the uid
                    // claim and the client never sends it. An applicant holding ROLE_USER is who
                    // uploads a licence, so this cannot be gated on a clinical role.
                    //
                    // IT MUST SIT ABOVE THE METHOD RULES BELOW, for the sharper version of the
                    // /api/profile reason: step 3 is a POST, so without this line EVERY APPLICANT IS
                    // 403'd UPLOADING THEIR OWN CREDENTIALS while the list GET keeps working on
                    // `/api/** -> .authenticated()`. The asymmetry reads as a broken upload rather
                    // than as a missing rule.
                    //
                    // TWO PATTERNS, AND THE PREFIX IS A WIDENING THIS PATH ACTUALLY NEEDS. Unlike
                    // /api/profile, which is the exact path and nothing under it, this resource has a
                    // real sub-resource -- /{id}/content, the only route by which document bytes leave
                    // this service. So the prefix is required, and the plural literal is spelled too
                    // because "/api/personal-document/**" does not match "/api/personal-document".
                    //
                    // /api/personal-document IS NOT /api/personal-documents, and this is the line that
                    // depends on it. The PLURAL path is PersonalDocumentResource -- the generated CRUD
                    // surface, whose three GETs carry no @PreAuthorize and return `data` INLINE with no
                    // owner check (profile-addendum.md S1). A matcher that reached it would hand that
                    // surface to a role-less applicant on top of the clinical roles that can already
                    // read it. ClinicalAuthorityMatrixIT asserts the separation in both directions,
                    // because it is a claim about Spring's pattern matching and not about this comment.
                    //
                    // METHOD-AGNOSTIC, SO HEAD IS COVERED, exactly as on /api/profile above: Spring MVC
                    // dispatches a HEAD to the @GetMapping handler, and a rule scoped to HttpMethod.GET
                    // lets it fall to whatever sits below (item 143's measured fail-open). A body-less
                    // read of a document list is still a read. Add a verb here, never an exception below.
                    //
                    // The handlers carry matching @PreAuthorize("isAuthenticated()"). Two layers, as
                    // api/ PR #55 settled, and neither is deletable on the strength of the other.
                    .requestMatchers("/api/personal-document", "/api/personal-document/**").authenticated()
                    // EVERY READ ON /api/profiles NAMES ITS SUBJECT IN THE PATH, so authentication
                    // gates nothing: every caller is authenticated as somebody and the subject is
                    // whoever they ask for. Held at .authenticated() below, a carer read a doctor's
                    // 21 fields — cardNumber, birthDate, address, emergencyContact, mobilePhone —
                    // and the collection read paged the whole clinician directory at once. The three
                    // gateways share one signing key and not a user store, so "any authenticated
                    // caller" here is every account in hc-admin and hc-patient too, and hc-admin
                    // dials this service directly over infranet where the gateway's own
                    // CLINICAL_AND_ADMIN rule on /services/** never runs. docs/backlog.md item 143.
                    //
                    // GATED BY PATH AND METHOD RATHER THAN PER HANDLER, deliberately: a GET added to
                    // ProfileResource later is gated the day it is written, which is the property the
                    // item asks for — the projection is the whole document, so a field added to
                    // Profile is published by this resource with no edit to review. The handlers
                    // carry the matching @PreAuthorize so the requirement is legible where the code
                    // is, the way AccountIdMigrationResource does under /api/admin.
                    //
                    // HEAD IS LISTED BESIDE GET AND THE OMISSION WAS A REAL FAIL-OPEN, found in review.
                    // Spring MVC dispatches a HEAD to the @GetMapping handler, so a rule scoped to GET
                    // alone let HEAD fall through to /api/** → .authenticated() below. That is not a
                    // technicality on this resource: the /email/{email} oracle answers entirely on the
                    // STATUS LINE — measured on quality, a carer's HEAD of a known address returned 200
                    // and of an unknown one 404 — so "does this person work here" leaks with no body at
                    // all, and HEAD of the collection returned 200 carrying X-Total-Count, the directory
                    // size. The @PreAuthorize layer did refuse it, because method security intercepts
                    // the invocation whatever the verb; the point is that the LAYER THIS COMMENT CALLS
                    // OPERATIVE did not, so deleting the annotations on the strength of the paragraph
                    // above would have reopened the oracle with every committed test still green.
                    // ProfileResourceIT pins both verbs now. Add a verb here, not an exception below.
                    //
                    // WRITES ARE UNTOUCHED and still admit CLINICAL_MUTATION's six through the rules
                    // below. What was decided is who may READ a profile that is not theirs.
                    //
                    // A CLINICIAN'S OWN PROFILE IS NOT REACHED THROUGH HERE and never was:
                    // GET /api/onboarding/profile resolves the caller from the uid claim and takes no
                    // subject at all, so it cannot name anyone else and stays .authenticated() above.
                    // Do not add a self-exception to this rule — a subject-addressed endpoint that
                    // excuses the caller has to compare caller against path, which is the shape that
                    // gets it wrong. See ProfileResource and ClinicalAuthorityMatrixIT.
                    .requestMatchers(HttpMethod.GET, "/api/profiles", "/api/profiles/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .requestMatchers(HttpMethod.HEAD, "/api/profiles", "/api/profiles/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    // The estate-wide recipient directory: account id, LOGIN and role for every
                    // ACTIVE professional, unpaginated. The login is what /api/authenticate takes,
                    // so an unauthorised read of this is the estate's valid-login list. All nine
                    // rather than CLINICAL_MUTATION's six, because it is a read and because the
                    // mobile recipient picker is what a carer composes from. Nine and not ten since
                    // 2026-09-06: ROLE_ANGEL left CLINICAL_AND_ADMIN — an angel is a proxy for one
                    // named patient, not a colleague in the estate directory (item 30) — and left
                    // this subsystem entirely on 2026-09-08 (item 44). A token still carrying it is
                    // refused here because the list is positive, which is the same answer.
                    .requestMatchers(HttpMethod.GET, "/api/messaging/recipients").hasAnyAuthority(AuthoritiesConstants.CLINICAL_AND_ADMIN)
                    // Starting a thread writes into OTHER PEOPLE'S inboxes — including a
                    // recipientRole broadcast to every nurse or doctor, with a push notification
                    // behind it. Exact path, so a reply into a thread the caller is already a member
                    // of (/conversations/{id}/messages) is unaffected: that one is own-scoped, and
                    // MessagingService.reply refuses a non-member with a 404 whatever this says.
                    .requestMatchers(HttpMethod.POST, "/api/messaging/conversations").hasAnyAuthority(AuthoritiesConstants.CLINICAL_AND_ADMIN)
                    // Everything else under messaging is correspondence, not clinical data, and is
                    // scoped to the caller's own MessageRecipient rows. Under the POST /api/** rule
                    // below, carer/chemist/technician could receive a message and never answer one;
                    // this is the same exception onboarding already makes.
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
                    // above the POST /api/** rule below: otherwise a carer, chemist or
                    // technician — every read-only role — gets a silent 403 registering a device
                    // and simply never receives notifications, with nothing to point at.
                    .requestMatchers("/api/notifications/**").authenticated()
                    // Booking leave is not a clinical mutation either. Fourth exception of the same
                    // shape, and the one with the plainest consequence: under the POST /api/** rule
                    // below, a carer, chemist or technician could not ASK FOR TIME OFF.
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
