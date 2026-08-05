package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.messaging.FirebaseMessaging;
import java.util.List;
import java.util.Map;
import net.jojoaddison.config.ApplicationProperties;
import net.jojoaddison.domain.DeviceToken;
import net.jojoaddison.repository.DeviceTokenRepository;
import net.jojoaddison.service.PushNotificationService.PushPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Transport routing in {@link PushNotificationService} (MOB9).
 *
 * <p>The bug these exist to prevent: sending an iOS device through FCM. Its token is an APNs token,
 * FCM rejects it, and the rejection is indistinguishable from a dead token — so the device gets
 * disabled and never notified again, with nothing to point at.
 */
class PushNotificationServiceTest {

    private static final String ACCOUNT = "push-nurse";

    private DeviceTokenRepository deviceTokenRepository;
    private ApnsClient apnsClient;
    private FirebaseMessaging firebaseMessaging;
    private ApplicationProperties applicationProperties;
    private PushNotificationService service;

    @BeforeEach
    void setUp() {
        deviceTokenRepository = Mockito.mock(DeviceTokenRepository.class);
        apnsClient = Mockito.mock(ApnsClient.class);
        firebaseMessaging = Mockito.mock(FirebaseMessaging.class);
        applicationProperties = new ApplicationProperties();
        applicationProperties.getNotifications().getPush().setEnabled(true);
        when(apnsClient.isConfigured()).thenReturn(true);
        when(apnsClient.send(anyString(), any(), any(), any(), any())).thenReturn(new ApnsClient.ApnsResult(true, false, null));

        @SuppressWarnings("unchecked")
        ObjectProvider<FirebaseMessaging> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(firebaseMessaging);

        service = new PushNotificationService(deviceTokenRepository, applicationProperties, apnsClient, provider);
    }

    private DeviceToken device(String token, String platform) {
        DeviceToken device = new DeviceToken();
        device.setToken(token);
        device.setPlatform(platform);
        device.setAccountId(ACCOUNT);
        return device;
    }

    private void registered(DeviceToken... devices) {
        when(deviceTokenRepository.findAllByAccountIdAndDisabledAtIsNull(ACCOUNT)).thenReturn(List.of(devices));
    }

    private PushPayload payload() {
        return new PushPayload("t.key", "b.key", "Messages", "You have a new message", "c1", Map.of("messageId", "m1"));
    }

    @Test
    void anIosDeviceGoesToAPNS_neverToFcm() throws Exception {
        registered(device("apns-token", "IOS"));

        service.sendToAccount(ACCOUNT, payload());

        verify(apnsClient).send(eq("apns-token"), any(), any(), any(), any());
        verify(firebaseMessaging, never()).sendEachForMulticast(any());
    }

    @Test
    void anAndroidDeviceGoesToFCM_neverToApns() throws Exception {
        registered(device("fcm-token", "ANDROID"));

        service.sendToAccount(ACCOUNT, payload());

        verify(firebaseMessaging).sendEachForMulticast(any());
        verify(apnsClient, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void anAccountWithBothPlatformsReachesBoth() throws Exception {
        registered(device("apns-token", "IOS"), device("fcm-token", "ANDROID"));

        service.sendToAccount(ACCOUNT, payload());

        verify(apnsClient).send(eq("apns-token"), any(), any(), any(), any());
        verify(firebaseMessaging).sendEachForMulticast(any());
    }

    @Test
    void anUnknownPlatformFallsBackToFcm() throws Exception {
        // A web or unset platform is the Android/FCM case; only an explicit IOS routes to Apple.
        registered(device("web-token", "WEB"));

        service.sendToAccount(ACCOUNT, payload());

        verify(firebaseMessaging).sendEachForMulticast(any());
        verify(apnsClient, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void platformMatchingIsCaseInsensitive() throws Exception {
        registered(device("apns-token", "ios"));

        service.sendToAccount(ACCOUNT, payload());

        verify(apnsClient).send(eq("apns-token"), any(), any(), any(), any());
        verify(firebaseMessaging, never()).sendEachForMulticast(any());
    }

    @Test
    void apnsSayingTheTokenIsDeadDisablesIt() {
        registered(device("gone", "IOS"));
        when(apnsClient.send(anyString(), any(), any(), any(), any())).thenReturn(new ApnsClient.ApnsResult(false, true, "Unregistered"));

        service.sendToAccount(ACCOUNT, payload());

        ArgumentCaptor<DeviceToken> captor = ArgumentCaptor.forClass(DeviceToken.class);
        verify(deviceTokenRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
        assertThat(captor.getValue().getDisabledReason()).isEqualTo("Unregistered");
    }

    @Test
    void aTRANSIENTapnsFailureDoesNotDisableTheDevice() {
        // A 503 from Apple must not cost the clinician every future notification.
        registered(device("fine", "IOS"));
        when(apnsClient.send(anyString(), any(), any(), any(), any())).thenReturn(
            new ApnsClient.ApnsResult(false, false, "ServiceUnavailable")
        );

        service.sendToAccount(ACCOUNT, payload());

        verify(deviceTokenRepository, never()).save(any());
    }

    @Test
    void anUnconfiguredApnsSkipsIosWithoutTouchingAndroid() throws Exception {
        when(apnsClient.isConfigured()).thenReturn(false);
        registered(device("apns-token", "IOS"), device("fcm-token", "ANDROID"));

        service.sendToAccount(ACCOUNT, payload());

        // One transport being unconfigured must not silence the other.
        verify(apnsClient, never()).send(any(), any(), any(), any(), any());
        verify(firebaseMessaging).sendEachForMulticast(any());
    }

    @Test
    void pushDisabledIsACleanNoOp() throws Exception {
        applicationProperties.getNotifications().getPush().setEnabled(false);
        registered(device("apns-token", "IOS"), device("fcm-token", "ANDROID"));

        service.sendToAccount(ACCOUNT, payload());

        verify(deviceTokenRepository, never()).findAllByAccountIdAndDisabledAtIsNull(any());
        verify(apnsClient, never()).send(any(), any(), any(), any(), any());
        verify(firebaseMessaging, never()).sendEachForMulticast(any());
    }

    @Test
    void anApnsThrowDoesNotPropagate() throws Exception {
        // sendToAccount is called from a Kafka consumer; an exception here must not poison it.
        registered(device("apns-token", "IOS"), device("fcm-token", "ANDROID"));
        when(apnsClient.send(anyString(), any(), any(), any(), any())).thenThrow(new IllegalStateException("apns down"));

        service.sendToAccount(ACCOUNT, payload());

        // ...and Android is still notified. One transport failing must not silence the other.
        verify(firebaseMessaging).sendEachForMulticast(any());
    }
}
