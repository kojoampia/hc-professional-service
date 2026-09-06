package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.management.SecurityMetersService;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The gate for {@code docs/backlog.md} item 27: a token this stack did not mint resolves to nothing, even when its
 * {@code sub} is a professional's login.
 *
 * <p>Three gateways share one HMAC signing key, so a token minted by hc-patient verifies here perfectly. hc-patient
 * grants {@code ROLE_USER} alongside {@code ROLE_PATIENT}, and {@code ROLE_USER} is exactly what an applicant here
 * holds — so such a token passes the {@code .authenticated()} onboarding island. Every own-scoped read in this
 * service then resolves the caller by matching {@code Profile.accountId} against {@code sub}, which is a login:
 * human-chosen, published by the recipient directory until item 19, and self-service to register on the sibling
 * stack. {@code /api/onboarding/profile} is the narrowest reachable surface and the one used here.</p>
 *
 * <p>{@code TokenOriginValidatorUnitTest} proves the decision table. This proves the wiring: that the validator is
 * actually attached to the decoder when the property is set, and — critically — that it is layered on top of the
 * default validators rather than replacing them. Handing a bare validator to {@code setJwtValidator} silently drops
 * the expiry check, which would be a considerably worse hole than the one being closed, and no unit test of the
 * validator itself could ever notice.</p>
 *
 * <p>Tokens are signed here with the same key the application is configured with, so signature verification passes
 * and the only thing under test is what the validators do afterwards.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "application.security.jwt.validate-origin=true" })
class TokenOriginValidationEnabledIT {

    /**
     * A login that exists on this stack. On the sibling stack it is whatever whoever registered there chose to type,
     * which is the entire attack: nothing has to be guessed, because logins are names.
     */
    private static final String COLLIDING_LOGIN = "nurse-jane";

    private static final String OUR_ISSUER = "hc-professional-gateway";
    private static final String OUR_AUDIENCE = "hc-professional";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @BeforeEach
    void seedTheProfileThatCanBeCollidedWith() {
        Profile profile = new Profile();
        profile.setAccountId(COLLIDING_LOGIN);
        profile.setFirstName("Jane");
        profile.setLastName("Doe");
        profileRepository.save(profile);
    }

    @AfterEach
    void cleanup() {
        profileRepository.findByAccountId(COLLIDING_LOGIN).ifPresent(profileRepository::delete);
    }

    @Test
    void aTokenFromThisGatewayResolvesToTheProfessionalsProfile() throws Exception {
        // The control. Without it, the assertion below would pass just as well against a broken endpoint.
        restMockMvc
            .perform(
                get("/api/onboarding/profile").header("Authorization", "Bearer " + token(OUR_ISSUER, OUR_AUDIENCE, COLLIDING_LOGIN, 3600))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(COLLIDING_LOGIN));
    }

    @Test
    void aSiblingStacksTokenWithACollidingSubResolvesToNothing() throws Exception {
        // Item 27, stated exactly: same signing key, valid signature, sub equal to a professional's login here, and
        // authorities a hc-patient account really holds. Before the iss claim there was nothing to tell it apart.
        restMockMvc
            .perform(
                get("/api/onboarding/profile").header(
                    "Authorization",
                    "Bearer " + token("hc-patient-gateway", "hc-patient", COLLIDING_LOGIN, 3600)
                )
            )
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anOriginRejectionIsCountedAsUntrustedOriginRatherThanLoggedAsUnknown() throws Exception {
        // The cutover has to be watchable. Until this meter existed a rejection matched none of SecurityJwtConfiguration's
        // message branches and landed in the else, so every live pre-claims session produced one ERROR labelled
        // "Unknown JWT error" and no metric at all — and "old tokens draining as expected" was indistinguishable from
        // "the issuer string is wrong and nobody can sign in".
        double before = untrustedOriginCount();

        restMockMvc
            .perform(
                get("/api/onboarding/profile").header(
                    "Authorization",
                    "Bearer " + token("hc-patient-gateway", "hc-patient", COLLIDING_LOGIN, 3600)
                )
            )
            .andExpect(status().isUnauthorized());

        assertThat(untrustedOriginCount()).isEqualTo(before + 1);
    }

    private double untrustedOriginCount() {
        return meterRegistry
            .get(SecurityMetersService.INVALID_TOKENS_METER_NAME)
            .tag(SecurityMetersService.INVALID_TOKENS_METER_CAUSE_DIMENSION, "untrusted-origin")
            .counter()
            .count();
    }

    @Test
    void aTokenWithoutTheClaimsIsRejected() throws Exception {
        // Every token this stack minted before 2026-09-06. Exactly why the flag defaults to off.
        restMockMvc
            .perform(get("/api/onboarding/profile").header("Authorization", "Bearer " + token(null, null, COLLIDING_LOGIN, 3600)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void expiryIsStillCheckedWithTheValidatorAttached() throws Exception {
        // The regression this class exists for: setJwtValidator REPLACES the validator, so attaching the origin
        // check without delegating to JwtValidators.createDefault() would stop expiry being enforced at all.
        restMockMvc
            .perform(
                get("/api/onboarding/profile").header(
                    "Authorization",
                    // An hour past, not a minute: JwtTimestampValidator allows 60 seconds of clock skew by default,
                    // so a token expiring 60 seconds ago sits exactly on the boundary this test depends on.
                    "Bearer " + token(OUR_ISSUER, OUR_AUDIENCE, COLLIDING_LOGIN, -3600)
                )
            )
            .andExpect(status().isUnauthorized());
    }

    private String token(String issuer, String audience, String subject, long secondsUntilExpiry) {
        byte[] keyBytes = Base64.from(jwtKey).decode();
        SecretKey key = new SecretKeySpec(keyBytes, 0, keyBytes.length, MacAlgorithm.HS512.getName());
        Instant now = Instant.now();
        Instant expiresAt = now.plus(secondsUntilExpiry, ChronoUnit.SECONDS);
        // A token that has already expired has to be dated from before it expired — NimbusJwtEncoder refuses a
        // claim set whose iat is after its exp — and far enough back that JwtTimestampValidator's 60-second default
        // clock skew cannot make the expired case look live.
        Instant issuedAt = expiresAt.minus(300, ChronoUnit.SECONDS);
        if (issuedAt.isAfter(now.minus(120, ChronoUnit.SECONDS))) {
            issuedAt = now.minus(120, ChronoUnit.SECONDS);
        }

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(subject)
            // ROLE_USER and nothing else: what an applicant holds here, and what hc-patient hands every patient
            // alongside ROLE_PATIENT. Rejecting ROLE_PATIENT is deliberately NOT the fix — which authorities the
            // sibling stacks mint is theirs to change.
            .claim(SecurityUtils.AUTHORITIES_KEY, "ROLE_USER");
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (audience != null) {
            claims.audience(List.of(audience));
        }

        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS512).build(), claims.build()))
            .getTokenValue();
    }
}
