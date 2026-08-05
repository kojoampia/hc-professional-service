package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import net.jojoaddison.config.ApplicationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ApnsClient} (MOB9) — the provider token, not the transport.
 *
 * <p>Only the parts that can fail silently are worth testing here: a JWT Apple rejects looks like
 * "notifications don't work" with a single opaque {@code InvalidProviderToken} to go on.
 */
class ApnsClientTest {

    @TempDir
    Path tempDir;

    private ApplicationProperties applicationProperties;
    private ECPublicKey publicKey;
    private Path keyFile;

    @BeforeEach
    void setUp() throws Exception {
        // A real P-256 key, PKCS#8 PEM — the same shape as Apple's .p8.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        publicKey = (ECPublicKey) pair.getPublic();

        String pem =
            "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded()) +
            "\n-----END PRIVATE KEY-----\n";
        keyFile = tempDir.resolve("AuthKey_TEST12345.p8");
        Files.writeString(keyFile, pem);

        applicationProperties = new ApplicationProperties();
        var apns = applicationProperties.getNotifications().getPush().getApns();
        apns.setKeyPath(keyFile.toString());
        apns.setKeyId("TEST12345");
        apns.setTeamId("TEAM123456");
    }

    private ApnsClient client() {
        return new ApnsClient(applicationProperties);
    }

    private String token(ApnsClient client) throws Exception {
        Method method = ApnsClient.class.getDeclaredMethod("authenticationToken");
        method.setAccessible(true);
        return (String) method.invoke(client);
    }

    @Test
    void isConfiguredOnlyWhenEveryPieceIsPresent() {
        assertThat(client().isConfigured()).isTrue();

        applicationProperties.getNotifications().getPush().getApns().setKeyId("");
        assertThat(client().isConfigured()).isFalse();
    }

    @Test
    void signsAVerifiableES256_token() throws Exception {
        SignedJWT jwt = SignedJWT.parse(token(client()));

        // The trap: signing with the JDK's SHA256withECDSA yields DER, which Apple rejects with an
        // opaque InvalidProviderToken. Nimbus emits the raw R||S form JOSE requires — this verifies
        // it round-trips as ES256 rather than merely being 'some signature'.
        assertThat(jwt.verify(new ECDSAVerifier(publicKey))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    }

    @Test
    void carriesTheKeyIdInTheHeaderAndTheTeamIdAsIssuer() throws Exception {
        SignedJWT jwt = SignedJWT.parse(token(client()));

        // Apple looks the key up by `kid` and scopes it by `iss`. Swapping the two is a silent 403.
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("TEST12345");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("TEAM123456");
        assertThat(jwt.getJWTClaimsSet().getIssueTime()).isNotNull();
    }

    @Test
    void carriesNO_expiryClaim() throws Exception {
        // Apple derives age from `iat` alone and rejects a token that carries `exp`.
        assertThat(SignedJWT.parse(token(client())).getJWTClaimsSet().getExpirationTime()).isNull();
    }

    @Test
    void reusesTheTokenRatherThanMintingOnePerNotification() throws Exception {
        // Apple rate-limits provider-token generation; a token per send would get throttled.
        ApnsClient client = client();
        assertThat(token(client)).isEqualTo(token(client));
    }

    @Test
    void sendingWithNoCredentialIsACleanNoOp() {
        applicationProperties.getNotifications().getPush().getApns().setKeyPath("");

        ApnsClient.ApnsResult result = client().send("tok", "t", "b", "c1", java.util.Map.of());

        // No exception, no network call — absence of credentials is a supported configuration.
        assertThat(result.delivered()).isFalse();
        assertThat(result.tokenIsDead()).isFalse();
        assertThat(result.reason()).isEqualTo("apns-not-configured");
    }

    @Test
    void anUnreadableKeyFailsTheSendWithoutThrowing() throws Exception {
        Files.writeString(keyFile, "not a key");

        ApnsClient.ApnsResult result = client().send("tok", "t", "b", "c1", java.util.Map.of());

        // A misconfigured credential must degrade the notification, not the write that caused it.
        assertThat(result.delivered()).isFalse();
        assertThat(result.tokenIsDead()).isFalse();
    }

    @Test
    void productionAndSandboxAreDifferentHOSTS() throws Exception {
        // The classic APNs mistake: a TestFlight build's token returns BadDeviceToken against
        // production, which is indistinguishable from an expired token.
        Method host = ApnsClient.class.getDeclaredMethod("host");
        host.setAccessible(true);

        applicationProperties.getNotifications().getPush().getApns().setProduction(true);
        assertThat((String) host.invoke(client())).isEqualTo("https://api.push.apple.com");

        applicationProperties.getNotifications().getPush().getApns().setProduction(false);
        assertThat((String) host.invoke(client())).isEqualTo("https://api.sandbox.push.apple.com");
    }

    @Test
    void truncatesAnOverlongCollapseId() throws Exception {
        // APNs caps it at 64 bytes and rejects the whole notification if it is longer — a missing
        // collapse is better than a missing notification.
        Method truncate = ApnsClient.class.getDeclaredMethod("truncateCollapseId", String.class);
        truncate.setAccessible(true);

        assertThat((String) truncate.invoke(client(), "c".repeat(200))).hasSize(64);
        assertThat((String) truncate.invoke(client(), (Object) null)).isEqualTo("messages");
        assertThat((String) truncate.invoke(client(), "conv-1")).isEqualTo("conv-1");
    }
}
