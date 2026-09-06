package net.jojoaddison.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Profile;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * The shipped default, stated out loud: with {@code application.security.jwt.validate-origin} off, a sibling stack's
 * token whose {@code sub} collides with a professional's login still reads that professional's profile.
 *
 * <p><b>This asserts that the defect is open, and it is meant to.</b> The claims and the validator shipped together
 * on 2026-09-06, but the validator is off by default because turning it on rejects every token minted before the
 * claims existed — which on the day of the release is every live session. So nothing is closed by deploying this
 * code; it is closed by setting the flag, once the claims have been live longer than the longest token lifetime.
 * Until then this test is the honest record of where the estate stands, and it is the companion to
 * {@link TokenOriginValidationEnabledIT}, which shows the same request refused with the flag on.</p>
 *
 * <p><b>When the default flips to on, delete this class</b> rather than adjusting it — an expectation that a
 * cross-stack token is accepted has no business surviving the decision that it should not be.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
class TokenOriginValidationDisabledIT {

    private static final String COLLIDING_LOGIN = "nurse-jane-default";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

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
    void withValidationOffASiblingStacksTokenStillReadsTheProfessionalsProfile() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/profile").header("Authorization", "Bearer " + siblingToken(COLLIDING_LOGIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(COLLIDING_LOGIN));
    }

    /** hc-patient's shape: its issuer, its audience, and the ROLE_USER it grants every patient. */
    private String siblingToken(String subject) {
        byte[] keyBytes = Base64.from(jwtKey).decode();
        SecretKey key = new SecretKeySpec(keyBytes, 0, keyBytes.length, MacAlgorithm.HS512.getName());
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuedAt(now.minus(120, ChronoUnit.SECONDS))
            .expiresAt(now.plus(3600, ChronoUnit.SECONDS))
            .subject(subject)
            .issuer("hc-patient-gateway")
            .audience(List.of("hc-patient"))
            .claim(SecurityUtils.AUTHORITIES_KEY, "ROLE_USER")
            .build();

        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS512).build(), claims))
            .getTokenValue();
    }
}
