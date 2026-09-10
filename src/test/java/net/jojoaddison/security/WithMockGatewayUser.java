package net.jojoaddison.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code @WithMockUser} for a service whose callers are identified by a JWT claim — the shape this
 * service actually receives, since backlog.md item 50.
 *
 * <p><b>Why {@code @WithMockUser} stopped being enough.</b> It installs a
 * {@code UsernamePasswordAuthenticationToken} whose principal is a string, so
 * {@code SecurityUtils.getCurrentUserLogin()} works and {@code getCurrentAccountId()} — which reads
 * the {@code uid} claim off a {@link Jwt} — returns empty. Every own-scoped endpoint would answer
 * 401 under it, correctly. This installs the real thing: a {@link JwtAuthenticationToken} over a
 * {@link Jwt} carrying {@code sub}, {@code iss} and {@code uid}, which is byte-for-byte the claim set
 * {@code TokenProvider} mints in {@code gateway/}.
 *
 * <p><b>The account id defaults to something that is not the login, and that is the point.</b>
 * {@link #ACCOUNT_ID_PREFIX} makes every test in the suite a check on item 50 without anyone writing
 * an assertion: an implementation that fell back to the JWT subject would look up
 * {@code "some-login"} against rows stored under {@code "uid-some-login"} and find nothing, so the
 * test fails rather than passing on a value that happens to match. A test that needs the two to be a
 * specific pair states both.
 *
 * @see WithUnauthenticatedMockUser
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockGatewayUser.Factory.class)
public @interface WithMockGatewayUser {
    /** Prefix for the derived account id. Chosen so it can never be mistaken for a login. */
    String ACCOUNT_ID_PREFIX = "uid-";

    /** The JWT subject — the gateway login. Audit fields and display names come from this. */
    String login() default "user";

    /** The {@code uid} claim. Empty derives {@link #ACCOUNT_ID_PREFIX} + {@link #login()}. */
    String accountId() default "";

    String[] authorities() default {};

    /**
     * The {@code iss} claim. Anything but {@code SecurityUtils.MINTING_ISSUER} makes
     * {@code getCurrentAccountId()} discard the {@code uid}, which is how a test spells "a token from
     * hc-admin or hc-patient".
     */
    String issuer() default SecurityUtils.MINTING_ISSUER;

    /** Omits the {@code uid} claim entirely — a token minted before 2026-09-07. */
    boolean withoutAccountId() default false;

    class Factory implements WithSecurityContextFactory<WithMockGatewayUser> {

        /**
         * The account id this annotation derives for a login, for a test that has to state the
         * stored value itself — a fixture it saves, or an assertion about which row was read.
         *
         * <p>On {@code Factory} rather than on the annotation because an annotation interface cannot
         * declare a method. Static-import it.
         */
        public static String accountIdFor(String login) {
            return ACCOUNT_ID_PREFIX + login;
        }

        /**
         * The same authentication this annotation installs, for a plain unit test that manages the
         * {@link SecurityContextHolder} itself rather than going through Spring's test context.
         *
         * @param accountId the {@code uid} claim, or null to omit it — a pre-2026-09-07 token.
         */
        public static JwtAuthenticationToken authenticationFor(String login, String accountId, String... authorities) {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", login);
            claims.put("iss", SecurityUtils.MINTING_ISSUER);
            if (accountId != null) {
                claims.put(SecurityUtils.UID_KEY, accountId);
            }
            Jwt jwt = new Jwt("test-token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "HS512"), claims);
            List<SimpleGrantedAuthority> granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
            return new JwtAuthenticationToken(jwt, granted, login);
        }

        /**
         * The same authentication as a MockMvc request post-processor, for a test that varies the
         * caller within one method — {@code SecurityMockMvcRequestPostProcessors.user(login)} builds
         * a string principal with no claims and every own-scoped endpoint 401s under it.
         */
        public static RequestPostProcessor gatewayUser(String login, String... authorities) {
            return SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(login, accountIdFor(login), authorities));
        }

        @Override
        public SecurityContext createSecurityContext(WithMockGatewayUser annotation) {
            String accountId = annotation.withoutAccountId()
                ? null
                : (annotation.accountId().isEmpty() ? accountIdFor(annotation.login()) : annotation.accountId());
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", annotation.login());
            claims.put("iss", annotation.issuer());
            if (accountId != null) {
                claims.put(SecurityUtils.UID_KEY, accountId);
            }
            Jwt jwt = new Jwt("test-token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "HS512"), claims);
            List<SimpleGrantedAuthority> granted = Arrays.stream(annotation.authorities()).map(SimpleGrantedAuthority::new).toList();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new JwtAuthenticationToken(jwt, granted, annotation.login()));
            return context;
        }
    }
}
