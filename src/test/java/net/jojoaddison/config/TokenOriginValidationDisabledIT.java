package net.jojoaddison.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * The shipped default, stated out loud: with {@code application.security.jwt.validate-origin} off, a
 * sibling stack's token is still <em>authenticated</em> here — and, since backlog.md item 50, still
 * resolves to nobody.
 *
 * <p><b>This class used to assert that the defect was open, and it no longer is.</b> Until
 * 2026-09-10 a token minted by hc-patient whose {@code sub} collided with a professional's login
 * read that professional's profile, because this service resolved its caller by matching
 * {@code sub} against {@code Profile.accountId} and both stacks' logins live in one namespace.
 * Item 50 moved that lookup onto the {@code uid} claim, and
 * {@code SecurityUtils.getCurrentAccountId()} discards a {@code uid} from any issuer but this
 * stack's gateway — so a sibling's token names nobody here whatever its subject says.
 *
 * <p><b>That closes the collision without the flag, and the flag is still worth turning on.</b> The
 * two answer different questions and it is worth not conflating them: this one says <i>a sibling's
 * account identifier means nothing in this database</i>, which is permanent; the validator says
 * <i>a sibling's token does not authenticate here at all</i>, which is a deployment's decision and
 * also refuses the caller from the {@code .authenticated()} surfaces that carry no identity —
 * {@code GET /api/**} among them. {@link TokenOriginValidationEnabledIT} is that half.
 *
 * <p><b>Keep this class when the default flips</b>, unlike the note that used to stand here. It no
 * longer records an open defect; it records that the identity rule holds on its own, which is
 * exactly the property that would be lost if anyone reintroduced a login fallback.
 */
@IntegrationTest
@AutoConfigureMockMvc
class TokenOriginValidationDisabledIT {

    private static final String COLLIDING_LOGIN = "nurse-jane-default";

    /** What this stack's own gateway would have minted for that professional. */
    private static final String LOCAL_ACCOUNT_ID = "uid-nurse-jane-default";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @BeforeEach
    void seedTheProfileThatCanBeCollidedWith() {
        Profile profile = new Profile();
        profile.setAccountId(LOCAL_ACCOUNT_ID);
        profile.setFirstName("Jane");
        profile.setLastName("Doe");
        profileRepository.save(profile);
    }

    @AfterEach
    void cleanup() {
        profileRepository.findByAccountId(LOCAL_ACCOUNT_ID).ifPresent(profileRepository::delete);
    }

    /**
     * The collision, still attempted and now refused.
     *
     * <p>401 rather than 404: the caller is authenticated — the signing key is shared and the
     * validator is off — but resolves to no account, and {@code OnboardingResource} refuses at that
     * point rather than looking anything up. The distinction matters to whoever reads the log,
     * because a 404 would suggest the profile is missing.
     */
    @Test
    void withValidationOffASiblingStacksTokenNoLongerReadsTheProfessionalsProfile() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/profile").header("Authorization", "Bearer " + siblingToken(COLLIDING_LOGIN, null)))
            .andExpect(status().isUnauthorized());
    }

    /**
     * <b>And a sibling that mints a {@code uid} of its own does not get to use it here.</b> hc-patient's
     * gateway keys its own events on its own {@code User.id}; a value from that namespace naming a row
     * in this one would be a coincidence, so the issuer filter drops it. Spelled as its own case
     * because the one above passes for the weaker reason that the claim is absent.
     */
    @Test
    void aSiblingsOwnUidClaimIsDiscardedRatherThanTrusted() throws Exception {
        restMockMvc
            .perform(get("/api/onboarding/profile").header("Authorization", "Bearer " + siblingToken(COLLIDING_LOGIN, LOCAL_ACCOUNT_ID)))
            .andExpect(status().isUnauthorized());
    }

    /** hc-patient's shape: its issuer, its audience, and the ROLE_USER it grants every patient. */
    private String siblingToken(String subject, String uid) {
        byte[] keyBytes = Base64.from(jwtKey).decode();
        SecretKey key = new SecretKeySpec(keyBytes, 0, keyBytes.length, MacAlgorithm.HS512.getName());
        Instant now = Instant.now();

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuedAt(now.minus(120, ChronoUnit.SECONDS))
            .expiresAt(now.plus(3600, ChronoUnit.SECONDS))
            .subject(subject)
            .issuer("hc-patient-gateway")
            .audience(List.of("hc-patient"))
            .claim(SecurityUtils.AUTHORITIES_KEY, "ROLE_USER");
        if (uid != null) {
            claims.claim(SecurityUtils.UID_KEY, uid);
        }

        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS512).build(), claims.build()))
            .getTokenValue();
    }
}
