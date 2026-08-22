package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.jojoaddison.broker.DomainEventEnvelope;
import net.jojoaddison.service.PushNotificationService.PushPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for {@link PushEventHandler} (MOB9).
 */
class PushEventHandlerTest {

    private static final String NURSE = "push-nurse";

    private PushNotificationService pushNotificationService;
    private ProfileService profileService;
    private PushEventHandler handler;

    @BeforeEach
    void setUp() {
        pushNotificationService = Mockito.mock(PushNotificationService.class);
        profileService = Mockito.mock(ProfileService.class);
        when(profileService.wantsMessagePush(any())).thenReturn(true);
        when(profileService.wantsCompliancePush(any())).thenReturn(true);
        when(profileService.wantsSenderNameInPush(any())).thenReturn(false);
        handler = new PushEventHandler(pushNotificationService, profileService);
    }

    private DomainEventEnvelope messageCreated(String eventId, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(
            Map.of("messageId", "m1", "conversationId", "c1", "recipientId", NURSE, "senderName", "Dr Mensah")
        );
        payload.putAll(extra);
        return new DomainEventEnvelope(eventId, "message.created", Instant.now(), "professionalservice", "dr.mensah", payload);
    }

    private PushPayload captured() {
        ArgumentCaptor<PushPayload> captor = ArgumentCaptor.forClass(PushPayload.class);
        verify(pushNotificationService).sendToAccount(eq(NURSE), captor.capture());
        return captor.getValue();
    }

    @Test
    void pushesAMessageToItsRecipient() {
        handler.handle(messageCreated(UUID.randomUUID().toString(), Map.of()));

        PushPayload payload = captured();
        assertThat(payload.data()).containsEntry("messageId", "m1").containsEntry("conversationId", "c1");
    }

    @Test
    void carriesIDENTIFIERSonly_neverContent() {
        handler.handle(messageCreated(UUID.randomUUID().toString(), Map.of("body", "Patient deteriorating on ward B")));

        PushPayload payload = captured();
        assertThat(payload.data().values()).noneMatch(value -> value.contains("deteriorating"));
        // The body is a bundle key and, at most, a sender's name — there is nowhere for content to
        // ride along even if a future event carried it.
        assertThat(payload.bodyArgs()).noneMatch(argument -> argument.contains("deteriorating"));
    }

    @Test
    void defaultBodyRevealsNoSender() {
        // A lock screen is visible to whoever is holding the phone, so even a colleague's name
        // has to be opted into rather than inherited.
        handler.handle(messageCreated(UUID.randomUUID().toString(), Map.of()));

        PushPayload payload = captured();
        assertThat(payload.bodyCode()).isEqualTo("push.message.body");
        assertThat(payload.bodyArgs()).isEmpty();
    }

    @Test
    void namesTheSenderOnlyWhenTheRecipientOptedIn() {
        when(profileService.wantsSenderNameInPush(NURSE)).thenReturn(true);

        handler.handle(messageCreated(UUID.randomUUID().toString(), Map.of()));

        PushPayload payload = captured();
        assertThat(payload.bodyCode()).isEqualTo("push.message.body.named");
        assertThat(payload.bodyArgs()).containsExactly("Dr Mensah");
    }

    @Test
    void collapsesPerConversationSoOneThreadIsOneTrayRow() {
        handler.handle(messageCreated(UUID.randomUUID().toString(), Map.of()));

        assertThat(captured().collapseKey()).isEqualTo("c1");
    }

    @Test
    void IGNORES_a_redelivered_event() {
        // Delivery is at-least-once. Without the dedupe a redelivery would show a second
        // notification for one message.
        String eventId = UUID.randomUUID().toString();
        handler.handle(messageCreated(eventId, Map.of()));
        handler.handle(messageCreated(eventId, Map.of()));

        verify(pushNotificationService, times(1)).sendToAccount(eq(NURSE), any());
    }

    @Test
    void stillHandlesADifferentEventAfterADuplicate() {
        String eventId = UUID.randomUUID().toString();
        handler.handle(messageCreated(eventId, Map.of()));
        handler.handle(messageCreated(eventId, Map.of()));
        handler.handle(messageCreated(UUID.randomUUID().toString(), Map.of()));

        verify(pushNotificationService, times(2)).sendToAccount(eq(NURSE), any());
    }

    @Test
    void respectsAnOptOut() {
        when(profileService.wantsMessagePush(NURSE)).thenReturn(false);

        handler.handle(messageCreated(UUID.randomUUID().toString(), Map.of()));

        verify(pushNotificationService, never()).sendToAccount(any(), any());
    }

    @Test
    void ignoresAMessageWithNoRecipient() {
        Map<String, Object> payload = new HashMap<>(Map.of("messageId", "m1"));
        handler.handle(new DomainEventEnvelope("e1", "message.created", Instant.now(), "s", "a", payload));

        verify(pushNotificationService, never()).sendToAccount(any(), any());
    }

    @Test
    void ignoresEventTypesItDoesNotHandle() {
        // The topic is shared by design; entity.created is not an error here.
        handler.handle(
            new DomainEventEnvelope("e2", "entity.created", Instant.now(), "s", "a", Map.of("entityId", "x", "accountId", NURSE))
        );

        verify(pushNotificationService, never()).sendToAccount(any(), any());
    }

    @Test
    void pushesAComplianceAlertToItsOwner() {
        handler.handle(
            new DomainEventEnvelope(
                "e3",
                "compliance.alert",
                Instant.now(),
                "s",
                "system",
                Map.of("alertType", "license-expiring", "entityId", "d1", "accountId", NURSE)
            )
        );

        ArgumentCaptor<PushPayload> captor = ArgumentCaptor.forClass(PushPayload.class);
        verify(pushNotificationService).sendToAccount(eq(NURSE), captor.capture());
        assertThat(captor.getValue().data()).containsEntry("alertType", "license-expiring");
    }

    @Test
    void ignoresAnAdminScopedComplianceAlertWithNoOwner() {
        // A sweep has no single account to notify. Not an error.
        handler.handle(new DomainEventEnvelope("e4", "compliance.alert", Instant.now(), "s", "system", Map.of("alertType", "sweep")));

        verify(pushNotificationService, never()).sendToAccount(any(), any());
    }

    @Test
    void survivesANullEnvelope() {
        handler.handle(null);
        verify(pushNotificationService, never()).sendToAccount(any(), any());
    }

    @Test
    void aFailingSendDoesNotPropagate() {
        // A notification failure must never poison the consumer or fail the write that produced
        // the event.
        Mockito.doThrow(new IllegalStateException("firebase down")).when(pushNotificationService).sendToAccount(any(), any());

        handler.handle(messageCreated(UUID.randomUUID().toString(), Map.of()));
    }
}
