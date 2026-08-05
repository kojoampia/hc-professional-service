package net.jojoaddison.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.jojoaddison.config.ApplicationProperties;
import net.jojoaddison.domain.DeviceToken;
import net.jojoaddison.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Sends push notifications through FCM.
 *
 * <p><b>APNs goes through FCM too.</b> Uploading the APNs auth key into the Firebase console gives
 * one code path, one credential and one retry policy; a separate APNs HTTP/2 client would be a
 * week of work for no behavioural gain.
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

    /** Errors that mean the token is dead rather than the send being transiently broken. */
    private static final List<String> DEAD_TOKEN_ERRORS = List.of("UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH");

    private final DeviceTokenRepository deviceTokenRepository;
    private final ApplicationProperties.Notifications.Push properties;

    /**
     * Resolved lazily so the application starts without a Firebase project. With push disabled the
     * bean is never present, and asking for it eagerly would defeat the point of the flag.
     */
    private final ObjectProvider<FirebaseMessaging> firebaseMessaging;

    public PushNotificationService(
        DeviceTokenRepository deviceTokenRepository,
        ApplicationProperties applicationProperties,
        ObjectProvider<FirebaseMessaging> firebaseMessaging
    ) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.properties = applicationProperties.getNotifications().getPush();
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

        FirebaseMessaging messaging = firebaseMessaging.getIfAvailable();
        if (messaging == null) {
            log.warn("Push is enabled but no FirebaseMessaging bean is available — check GOOGLE_APPLICATION_CREDENTIALS");
            return;
        }

        List<DeviceToken> devices = deviceTokenRepository.findAllByAccountIdAndDisabledAtIsNull(accountId);
        if (devices.isEmpty()) {
            return;
        }

        List<String> tokens = devices.stream().map(DeviceToken::getToken).limit(MAX_TOKENS_PER_REQUEST).toList();

        try {
            BatchResponse response = messaging.sendEachForMulticast(build(tokens, payload));
            pruneDeadTokens(devices, response);
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
            .setApnsConfig(
                ApnsConfig.builder()
                    .putHeader("apns-priority", "10")
                    .putHeader("apns-collapse-id", payload.collapseKey())
                    .setAps(
                        Aps.builder()
                            .setAlert(
                                ApsAlert.builder().setTitleLocalizationKey(payload.titleKey()).setLocalizationKey(payload.bodyKey()).build()
                            )
                            .setSound("default")
                            .setMutableContent(true)
                            .build()
                    )
                    .build()
            )
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
                DeviceToken device = devices.get(i);
                device.setDisabledAt(Instant.now());
                device.setDisabledReason(code);
                deviceTokenRepository.save(device);
                log.info("Disabled dead device token for {} ({})", device.getAccountId(), code);
            }
        }
    }
}
