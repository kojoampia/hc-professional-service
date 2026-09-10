package net.jojoaddison.security;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * Utility class for Spring Security.
 */
public final class SecurityUtils {

    public static final MacAlgorithm JWT_ALGORITHM = MacAlgorithm.HS512;

    public static final String AUTHORITIES_KEY = "auth";

    /**
     * The claim hc-patient's gateway mints so a service with no user management can tell <em>which
     * patient</em> is calling.
     *
     * <p>Read here for exactly one endpoint — {@code GET /api/duty-roster/customer/{customerId}},
     * the patient day plan — and nothing else in this service uses it. The three gateways share one
     * signing key, so a patient token validates here; the subject is a login that matches nothing in
     * this database, and the email is the only identifier the two stacks have in common. It is the
     * same chain hc-patient's own {@code PatientScope} follows, which is why it is spelled the same
     * way.
     *
     * <p>Tokens this stack's own gateway mints carry <b>no</b> {@code email} claim, so a clinician
     * asking for a customer day plan resolves to nobody and is refused — the correct answer, at no
     * coordination cost between the two products.
     */
    public static final String EMAIL_KEY = "email";

    /**
     * The claim this stack's own gateway mints carrying {@code User.id} — the account identifier
     * that survives a login being edited, and the value the gateway keys its account events on.
     *
     * <p>Added on 2026-09-07 ({@code backlog.md} item 48) to close the fork that made the two
     * producers on {@code hc.professional.registration} name one clinician differently. <b>Since
     * item 50 it is this service's notion of who is calling</b> — the value behind
     * {@code Profile.accountId}, {@code ProfessionalApplication.accountId},
     * {@code DeviceToken.accountId}, {@code Message.senderId} and
     * {@code MessageRecipient.recipientId}, and the only identifier any of them is looked up by.
     *
     * <p>The javadoc here used to argue the opposite, on three grounds. Two of them were true and
     * have been dealt with rather than disproved: the stored values were logins, which is what
     * {@code AccountIdMigrationService} moves; and the claim is absent from tokens minted before it
     * existed, which the owner answered on 2026-09-10 by directing a direct cutover — the product
     * launches in February 2027, no professional is on the platform, and a token with no claim is
     * meant to fail and be replaced by signing in again. The third is not a problem but the
     * mechanism: a {@code uid} minted by hc-admin or hc-patient names a row in <em>their</em> user
     * store, so {@link #MINTING_ISSUER} discards it and such a caller resolves to nobody here.
     *
     * <p><b>Do not add a fallback to the login.</b> That is the second join key this decision exists
     * to remove, reintroduced under the first one's name — {@code backlog.md} item 50, § Do not.
     *
     * @see #getCurrentAccountId()
     */
    public static final String UID_KEY = "uid";

    /**
     * The only issuer whose {@link #UID_KEY} means anything in this database — this stack's own
     * gateway, {@code TokenProvider.ISSUER}.
     *
     * <p><b>Not the same question as {@code application.security.jwt.trusted-issuers}, and
     * deliberately not configurable with it.</b> That list decides who may authenticate here, and a
     * deployment may well decide to trust hc-admin or hc-patient for that. It cannot make their
     * {@code User.id} name a row in {@code hcProfessionalGateway}: the three products share a
     * signing key, not a user store. So a {@code uid} from any other issuer is discarded rather than
     * stored — the same answer as no claim at all, which is a state this code already has to handle.
     *
     * <p>Costs nothing to require: this gateway has stamped {@code iss} since 2026-09-06 and
     * {@code uid} only since 2026-09-07, so no token exists that carries the second without the
     * first. The literal is duplicated from {@code ApplicationProperties} for the reason recorded
     * there — this repository has no {@code TokenProvider} to derive it from.
     */
    public static final String MINTING_ISSUER = "hc-professional-gateway";

    private SecurityUtils() {}

    /**
     * <b>Who is calling.</b> The gateway {@code User.id} on the caller's token — the value every
     * {@code accountId} in this database holds, and the estate's correlation key for a professional.
     *
     * <p><b>{@link Optional#empty()} means nobody, and a caller that resolves to nobody is refused.</b>
     * It is reachable three ways: a token minted before 2026-09-07, which carries no such claim; a
     * token minted by hc-admin or hc-patient, whose {@code uid} names a row in their user store and
     * is discarded by the {@link #MINTING_ISSUER} filter; and a machine path with no token at all.
     * The first is the direct cutover working as directed — signing in again mints a token that
     * carries the claim. The second is the correct answer for a caller with no account here.
     *
     * <p><b>Never substitute {@link #getCurrentUserLogin()} when this is empty.</b> The login is a
     * second identifier in a second space; falling back to it is precisely what {@code backlog.md}
     * item 50 removed, and it would resolve against migrated rows to nothing anyway — or, worse, to
     * a row belonging to whoever most recently took that login, since JHipster frees a login when
     * an account is deleted.
     *
     * <p>A blank claim is empty rather than an empty string, for the reason
     * {@link #getCurrentUserEmail()} gives: an empty string compared against stored data is a value
     * that could match something.
     */
    public static Optional<String> getCurrentAccountId() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        return Optional.ofNullable(securityContext.getAuthentication())
            .map(Authentication::getPrincipal)
            .filter(Jwt.class::isInstance)
            .map(Jwt.class::cast)
            .filter(jwt -> MINTING_ISSUER.equals(jwt.getClaimAsString(JwtClaimNames.ISS)))
            .map(jwt -> jwt.getClaimAsString(UID_KEY))
            .filter(uid -> !uid.isBlank());
    }

    /**
     * The {@code email} claim on the caller's token, when there is one.
     *
     * <p>A blank claim is {@link Optional#empty()} rather than an empty string: hc-patient's gateway
     * writes {@code ""} for an account with no email address, and an empty string compared against
     * stored data is a value that could match something.
     */
    public static Optional<String> getCurrentUserEmail() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        return Optional.ofNullable(securityContext.getAuthentication())
            .map(Authentication::getPrincipal)
            .filter(Jwt.class::isInstance)
            .map(Jwt.class::cast)
            .map(jwt -> jwt.getClaimAsString(EMAIL_KEY))
            .filter(email -> !email.isBlank());
    }

    /**
     * The login of the current user — <b>for the audit trail and for display, never as a key.</b>
     *
     * <p>Since {@code backlog.md} item 50 this is not who is calling; {@link #getCurrentAccountId()}
     * is. The login remains the right value for {@code createdBy}, {@code lastModifiedBy},
     * {@code OnboardingEvent.actor} and every event {@code actor} field, because a human reading a
     * trail needs a name rather than a Mongo id, and because those fields are never looked up.
     *
     * <p>The line between the two is worth stating in one place: <b>anything compared against stored
     * data uses the account id; anything written down for a person to read uses the login.</b>
     *
     * @return the login of the current user.
     */
    public static Optional<String> getCurrentUserLogin() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        return Optional.ofNullable(extractPrincipal(securityContext.getAuthentication()));
    }

    private static String extractPrincipal(Authentication authentication) {
        if (authentication == null) {
            return null;
        } else if (authentication.getPrincipal() instanceof UserDetails springSecurityUser) {
            return springSecurityUser.getUsername();
        } else if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        } else if (authentication.getPrincipal() instanceof String s) {
            return s;
        }
        return null;
    }

    /**
     * Get the JWT of the current user.
     *
     * @return the JWT of the current user.
     */
    public static Optional<String> getCurrentUserJWT() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        return Optional.ofNullable(securityContext.getAuthentication())
            .map(Authentication::getCredentials)
            .map(SecurityUtils::asTokenValue);
    }

    /**
     * The compact serialization of whatever credential the caller authenticated with, or null.
     *
     * <p><b>Two shapes, and this service only ever produces the second.</b> A {@code String} is what
     * JHipster's original {@code JWTFilter} left behind and what every test here constructed; a
     * {@link Jwt} is what {@code oauth2ResourceServer().jwt()} produces, via the
     * {@code JwtAuthenticationConverter} in {@code SecurityJwtConfiguration}. This method used to
     * accept only the first, so at runtime it returned empty on every single call.
     *
     * <p>That mattered because {@code PatientServiceClient} relays this token to {@code hc-patient}:
     * with none to relay it declines to call at all — correctly, since reading with no credential
     * would either 401 or, worse, succeed against an open endpoint and return data the caller was
     * never authorised for. So <b>every cross-stack read degraded silently to an empty list</b>: the
     * dashboard's patient count sat at zero, a round's customer snapshot never populated on write
     * (DR2) or on a day-view refresh (DR6), and the activity trail came back empty for a customer the
     * caller was entitled to (DR3). Nothing failed; it all just returned nothing, which is
     * indistinguishable from "no data yet".
     */
    private static String asTokenValue(Object credentials) {
        if (credentials instanceof Jwt jwt) {
            return jwt.getTokenValue();
        }
        // A blank string is not a credential. Treated as absent so the caller declines to relay it
        // rather than sending `Authorization: Bearer ` and getting a 401 it cannot explain.
        return credentials instanceof String token && !token.isBlank() ? token : null;
    }

    /**
     * Check if a user is authenticated.
     *
     * @return true if the user is authenticated, false otherwise.
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && getAuthorities(authentication).noneMatch(AuthoritiesConstants.ANONYMOUS::equals);
    }

    /**
     * Checks if the current user has any of the authorities.
     *
     * @param authorities the authorities to check.
     * @return true if the current user has any of the authorities, false otherwise.
     */
    public static boolean hasCurrentUserAnyOfAuthorities(String... authorities) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (
            authentication != null && getAuthorities(authentication).anyMatch(authority -> Arrays.asList(authorities).contains(authority))
        );
    }

    /**
     * Checks if the current user has none of the authorities.
     *
     * @param authorities the authorities to check.
     * @return true if the current user has none of the authorities, false otherwise.
     */
    public static boolean hasCurrentUserNoneOfAuthorities(String... authorities) {
        return !hasCurrentUserAnyOfAuthorities(authorities);
    }

    /**
     * Checks if the current user has a specific authority.
     *
     * @param authority the authority to check.
     * @return true if the current user has the authority, false otherwise.
     */
    public static boolean hasCurrentUserThisAuthority(String authority) {
        return hasCurrentUserAnyOfAuthorities(authority);
    }

    private static Stream<String> getAuthorities(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority);
    }
}
