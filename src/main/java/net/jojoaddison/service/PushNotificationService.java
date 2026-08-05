package net.jojoaddison.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.jojoaddison.config.ApplicationProperties;
import net.jojoaddison.domain.DeviceToken;
import net.jojoaddison.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Sends push notifications: FCM for Android, APNs directly for iOS.
 *
 * <p><b>Why the split.</b> {@code @capacitor/push-notifications} uses the FCM SDK on Android but
 * registers with APNs on iOS, so an iPhone hands back a raw APNs device token. FCM rejects those
 * with {@code INVALID_ARGUMENT} — the same code a genuinely dead token produces — so sending every
 * platform through FCM would have pruned each iOS device after its first notification, silently and
 * permanently. Reaching iOS through FCM would in any case require uploading the same .p8 key to
 * Firebase, so {@link ApnsClient} removes a hop rather than adding a credential.
 *
 * <p><b>Absence of credentials is a supported configuration, not a failure.</b>
 * {@code application.notifications.push.enabled} defaults to false and this returns before touching
 * Firebase, mirroring how {@code DomainEventPublisher} treats {@code application.kafka.enabled}. A
 * stack without a Firebase project must start cleanly rather than log a stack trace per event.
 *
 * <p><b>What a notification may contain.</b> Identifiers in the data payload; for the visible text,
 * either a generic string or — only when the recipient has opted in — the sender's name. Never a
 * message body, never a patient identifier. The device fetches the message over HTTP, which is what
 * keeps the authorization check in one place.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    /** FCM's per-request ceiling. Realistically a clinician has one to three devices. */
    private static final int MAX_TOKENS_PER_REQUEST = 500;

    /**
     * FCM errors that mean the token is dead rather than the send being transiently broken.
     *
     * <p>{@code INVALID_ARGUMENT} is deliberately <em>not</em> here. FCM returns it both for a
     * malformed token and for a malformed <em>message</em>, so treating it as fatal to the token
     * would let one bad payload field disable every Android device in the estate. A token that is
     * genuinely gone reports {@code UNREGISTERED}.
     */
    private static final List<String> DEAD_TOKEN_ERRORS = List.of("UNREGISTERED", "SENDER_ID_MISMATCH");

    /** {@link DeviceToken#getPlatform()} value that routes to APNs. Anything else goes to FCM. */
    private static final String IOS = "IOS";

    private final DeviceTokenRepository deviceTokenRepository;
    private final ApplicationProperties.Notifications.Push properties;
    private final ApnsClient apnsClient;

    /**
     * Resolved lazily so the application starts without a Firebase project. With push disabled the
     * bean is never present, and asking for it eagerly would defeat the point of the flag.
     */
    private final ObjectProvider<FirebaseMessaging> firebaseMessaging;

    public PushNotificationService(
        DeviceTokenRepository deviceTokenRepository,
        ApplicationProperties applicationProperties,
        ApnsClient apnsClient,
        ObjectProvider<FirebaseMessaging> firebaseMessaging
    ) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.properties = applicationProperties.getNotifications().getPush();
        this.apnsClient = apnsClient;
        this.firebaseMessaging = firebaseMessaging;
    }

    /** A notification to deliver. Text is already decided; this class does not compose copy. */
    public record PushPayload(
        String titleKey,
        String bodyKey,
        String fallbackTitle,
        String fallbackBody,
        String collapseKey,
        Map<String, String> data
    ) {}

    /**
     * Sends to every active device registered to an account.
     *
     * <p>Never throws: a failed notification must not roll back or retry the write that triggered
     * it, exactly as with the Kafka publisher.
     */
    public void sendToAccount(String accountId, PushPayload payload) {
        if (!properties.isEnabled()) {
            log.debug("Push disabled — not sending {} to {}", payload.collapseKey(), accountId);
            return;
        }
        if (accountId == null || accountId.isBlank()) {
            return;
        }

        List<DeviceToken> devices = deviceTokenRepository.findAllByAccountIdAndDisabledAtIsNull(accountId);
        if (devices.isEmpty()) {
            return;
        }

        Map<Boolean, List<DeviceToken>> byTransport = devices
            .stream()
            .collect(Collectors.partitioningBy(device -> IOS.equalsIgnoreCase(device.getPlatform())));

        sendViaApns(byTransport.get(true), payload);
        sendViaFcm(byTransport.get(false), accountId, payload);
    }

    /** iOS — one request per device; APNs has no multicast. */
    private void sendViaApns(List<DeviceToken> devices, PushPayload payload) {
        if (devices.isEmpty()) {
            return;
        }
        if (!apnsClient.isConfigured()) {
            log.warn("Push is enabled but APNs is not configured — {} iOS device(s) will not be notified", devices.size());
            return;
        }
        for (DeviceToken device : devices.stream().limit(MAX_TOKENS_PER_REQUEST).toList()) {
            try {
                ApnsClient.ApnsResult result = apnsClient.send(
                    device.getToken(),
                    payload.fallbackTitle(),
                    payload.fallbackBody(),
                    payload.collapseKey(),
                    payload.data()
                );
                if (result.tokenIsDead()) {
                    disable(device, result.reason());
                }
            } catch (Exception e) {
                // Per device, and inside the loop: one unreachable handset must not cost the
                // clinician's other devices, and neither transport may silence the other.
                log.error("APNs send failed for {}", device.getAccountId(), e);
            }
        }
    }

    /** Android — one multicast request for the account's devices. */
    private void sendViaFcm(List<DeviceToken> devices, String accountId, PushPayload payload) {
        if (devices.isEmpty()) {
            return;
        }
        FirebaseMessaging messaging = firebaseMessaging.getIfAvailable();
        if (messaging == null) {
            log.warn("Push is enabled but no FirebaseMessaging bean is available — check GOOGLE_APPLICATION_CREDENTIALS");
            return;
        }

        List<DeviceToken> targets = devices.stream().limit(MAX_TOKENS_PER_REQUEST).toList();
        List<String> tokens = targets.stream().map(DeviceToken::getToken).toList();

        try {
            BatchResponse response = messaging.sendEachForMulticast(build(tokens, payload));
            pruneDeadTokens(targets, response);
        } catch (Exception e) {
            // Includes FirebaseMessagingException. A push that cannot be delivered is a degraded
            // notification, not a failed operation.
            log.error("Push send failed for {}", accountId, e);
        }
    }

    private MulticastMessage build(List<String> tokens, PushPayload payload) {
        return MulticastMessage.builder()
            .addAllTokens(tokens)
            // Identifiers only. The client uses these to fetch the message over HTTP and to dedupe
            // against the STOMP frame for the same event.
            .putAllData(payload.data())
            .setNotification(Notification.builder().setTitle(payload.fallbackTitle()).setBody(payload.fallbackBody()).build())
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    // Ten messages in one thread must not become ten tray rows.
                    .setCollapseKey(payload.collapseKey())
                    .setNotification(
                        AndroidNotification.builder()
                            .setTitleLocalizationKey(payload.titleKey())
                            .setBodyLocalizationKey(payload.bodyKey())
                            .setChannelId("messages")
                            .build()
                    )
                    .build()
            )
            // No ApnsConfig: only Android tokens reach this builder. iOS is served by ApnsClient.
            .build();
    }

    /**
     * Disables tokens FCM reports as dead.
     *
     * <p>Without this the collection fills with tokens for uninstalled apps and every send degrades
     * — each one still costs a request slot and a response to parse.
     */
    private void pruneDeadTokens(List<DeviceToken> devices, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size() && i < devices.size(); i++) {
            SendResponse result = responses.get(i);
            if (result.isSuccessful() || result.getException() == null) {
                continue;
            }
            String code = result.getException().getMessagingErrorCode() == null
                ? result.getException().getErrorCode() == null ? "" : result.getException().getErrorCode().name()
                : result.getException().getMessagingErrorCode().name();

            if (DEAD_TOKEN_ERRORS.contains(code)) {
                disable(devices.get(i), code);
            } else {
                log.warn("FCM rejected a notification for {}: {}", devices.get(i).getAccountId(), code);
            }
        }
    }

    /** Takes a device out of the target set. Re-registering revives it — see DeviceTokenResource. */
    private void disable(DeviceToken device, String reason) {
        device.setDisabledAt(Instant.now());
        device.setDisabledReason(reason);
        deviceTokenRepository.save(device);
        log.info("Disabled dead device token for {} ({})", device.getAccountId(), reason);
    }
}
