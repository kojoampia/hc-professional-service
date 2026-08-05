package net.jojoaddison.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import net.jojoaddison.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Talks to APNs directly over HTTP/2.
 *
 * <p><b>Why not Firebase for iOS.</b> {@code @capacitor/push-notifications} registers with APNs on
 * iOS and hands back a raw APNs device token — FCM rejects those, and the rejection code
 * ({@code INVALID_ARGUMENT}) is indistinguishable from a dead token, so routing iOS through FCM
 * would have silently disabled every iPhone after its first notification. Reaching iOS via FCM
 * would also require uploading this same .p8 key to Firebase, so going direct removes a middleman
 * rather than adding a credential — and keeps the Firebase SDK out of the iOS binary entirely.
 *
 * <p><b>Authentication</b> is a short-lived ES256 JWT signed with the .p8 key, sent as a bearer
 * token. Apple requires it to be refreshed at least hourly and rejects one older than an hour, but
 * also rate-limits regeneration — so it is cached and rotated slightly early rather than minted per
 * request.
 *
 * <p>The JDK's {@link HttpClient} speaks HTTP/2 natively, which is all APNs needs; there is no
 * additional dependency here.
 */
@Service
public class ApnsClient {

    private static final Logger log = LoggerFactory.getLogger(ApnsClient.class);

    private static final String PRODUCTION_HOST = "https://api.push.apple.com";
    private static final String SANDBOX_HOST = "https://api.sandbox.push.apple.com";

    /** Apple rejects a token older than an hour; refresh before that with room to spare. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(50);

    private final ApplicationProperties.Notifications.Push.Apns properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;
    private volatile ECPrivateKey signingKey;

    public ApnsClient(ApplicationProperties applicationProperties) {
        this.properties = applicationProperties.getNotifications().getPush().getApns();
    }

    /** What APNs said about one device. */
    public record ApnsResult(boolean delivered, boolean tokenIsDead, String reason) {}

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * Sends one notification to one device token.
     *
     * <p>Never throws: a push that cannot be delivered is a degraded notification, not a failed
     * operation.
     */
    public ApnsResult send(String deviceToken, String title, String body, String collapseId, Map<String, String> data) {
        if (!isConfigured()) {
            return new ApnsResult(false, false, "apns-not-configured");
        }
        try {
            String payload = objectMapper.writeValueAsString(
                Map.of(
                    "aps",
                    Map.of("alert", Map.of("title", title, "body", body), "sound", "default", "mutable-content", 1),
                    // Identifiers only, matching the FCM path and the STOMP frame — the client
                    // fetches the message itself so the read goes through the same authorization.
                    "data",
                    data
                )
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host() + "/3/device/" + deviceToken))
                .header("authorization", "bearer " + authenticationToken())
                .header("apns-topic", properties.getBundleId())
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                // Ten messages in one thread must not become ten rows on the lock screen.
                .header("apns-collapse-id", truncateCollapseId(collapseId))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return interpret(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ApnsResult(false, false, "interrupted");
        } catch (Exception e) {
            log.error("APNs send failed", e);
            return new ApnsResult(false, false, e.getClass().getSimpleName());
        }
    }

    private ApnsResult interpret(HttpResponse<String> response) {
        if (response.statusCode() == 200) {
            return new ApnsResult(true, false, null);
        }
        String reason = extractReason(response.body());
        // 410 Unregistered is the app being uninstalled. 400 BadDeviceToken usually means the token
        // belongs to the other environment — the sandbox/production mix-up — which is why that is
        // called out on the `production` property rather than left to be rediscovered here.
        boolean dead = response.statusCode() == 410 || "BadDeviceToken".equals(reason) || "Unregistered".equals(reason);
        if (!dead) {
            log.warn("APNs rejected a notification: {} {}", response.statusCode(), reason);
        }
        return new ApnsResult(false, dead, reason);
    }

    private String extractReason(String body) {
        try {
            Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
            Object reason = parsed.get("reason");
            return reason == null ? "unknown" : reason.toString();
        } catch (Exception e) {
            return "unparseable";
        }
    }

    /**
     * APNs caps the collapse id at 64 bytes and rejects anything longer outright, which would turn
     * a long conversation id into a total delivery failure rather than a missing collapse.
     */
    private String truncateCollapseId(String collapseId) {
        String value = collapseId == null || collapseId.isBlank() ? "messages" : collapseId;
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private String host() {
        return properties.isProduction() ? PRODUCTION_HOST : SANDBOX_HOST;
    }

    /** Cached provider token, regenerated shortly before Apple would reject it. */
    private synchronized String authenticationToken() throws Exception {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }

        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(properties.getKeyId()).type(JOSEObjectType.JWT).build(),
            new JWTClaimsSet.Builder().issuer(properties.getTeamId()).issueTime(Date.from(now)).build()
        );
        // Nimbus emits ES256 in the raw R||S form JOSE requires. Signing with the JDK's
        // SHA256withECDSA directly would produce DER, which APNs rejects with an opaque
        // InvalidProviderToken — a well-known trap, avoided by using the library already here.
        jwt.sign(new ECDSASigner(privateKey()));

        cachedToken = jwt.serialize();
        cachedTokenExpiry = now.plus(TOKEN_LIFETIME);
        return cachedToken;
    }

    private ECPrivateKey privateKey() throws Exception {
        if (signingKey != null) {
            return signingKey;
        }
        String pem = Files.readString(Path.of(properties.getKeyPath()));
        String base64 = pem.replaceAll("-----BEGIN PRIVATE KEY-----", "").replaceAll("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        signingKey = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        return signingKey;
    }
}
