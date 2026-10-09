package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.jojoaddison.config.ApplicationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/**
 * ⭐ <b>The {@code onboarding.state} payload, and in particular that its role key is
 * {@code authority} (F-C).</b>
 *
 * <p>{@code profile.md} § Gap Update: <i>"Refactor the {@code String requestedRole} to
 * {@code authority}."</i> T3 renamed the entity field, the repository query, both service parameters
 * and both clients, and left the wire alone behind a javadoc claiming the old key was <i>"a published
 * cross-product contract on {@code hc.professional.registration} that hc-admin consumes"</i>.
 *
 * <p><b>It was not consumed.</b> Measured at hc-admin's checkout on 2026-10-09:
 * {@code requestedRole} appears nowhere in its {@code api/src/main} or {@code gateway/src/main}, and
 * {@code SiblingEventParser.parseProfessionalEvent} reads {@code accountId} and {@code state} and
 * nothing else off this payload. So the rename is the specification's and the exemption had no
 * ground.
 *
 * <p>⚠ <b>There was no test of this payload at all before F-C</b>, which is the more interesting
 * finding: the key could be renamed, misspelled or dropped and the only thing that would have
 * noticed was a reader. {@code ProfileStatusEventTest} beside this one asserts the <em>other</em>
 * half of the registration topic, and its {@code carriesNoRoleNoLicenceAndNoName} case already names
 * both {@code requestedRole} and {@code authority} among the keys that half must not carry — so the
 * rename cannot leak a role onto {@code ProfileStatus} without going red there.
 */
class OnboardingStateEventTest {

    private StreamBridge streamBridge;
    private DomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        streamBridge = mock(StreamBridge.class);
        publisher = new DomainEventPublisher(streamBridge, new ApplicationProperties());
    }

    /**
     * ⛔ The role rides as {@code authority}, and {@code requestedRole} is gone from the wire.
     *
     * <p>Both halves asserted: the presence, because that is the specified name, and the absence,
     * because a payload carrying the value under both names would satisfy a consumer of either and
     * leave two spellings of one field on a durable channel — which is how this estate's notes
     * describe arriving at one wrong copy.
     */
    @Test
    void theRoleKeyIsAuthorityAndNotRequestedRole() {
        publisher.publishOnboardingState("COMPLETED", "user-42", "app-7", "ROLE_NURSE", "ama.serwaa");

        Map<String, Object> payload = capturePayload();
        assertThat(payload).containsEntry("authority", "ROLE_NURSE").doesNotContainKey("requestedRole");
    }

    /** The whole payload, so a key added later is a decision rather than an accident. */
    @Test
    void carriesTheFourContractedKeysAndNoOthers() {
        publisher.publishOnboardingState("COMPLETED", "user-42", "app-7", "ROLE_NURSE", "ama.serwaa");

        DomainEventEnvelope envelope = captureEnvelope();
        assertThat(envelope.eventType()).isEqualTo("onboarding.state");
        assertThat(envelope.source()).isEqualTo("hc-professional-service");
        assertThat(envelope.actor()).isEqualTo("ama.serwaa");
        assertThat(envelope.payload()).containsOnlyKeys("accountId", "state", "applicationId", "authority");
        assertThat(envelope.payload()).containsEntry("accountId", "user-42").containsEntry("state", "COMPLETED");
    }

    /**
     * An absent role omits the key rather than sending it null — the rule
     * {@code publishProfileStatus} states for every unknown identifier on this topic, applied here
     * because an application may legitimately carry no authority yet.
     */
    @Test
    void anAbsentAuthorityOmitsTheKey() {
        publisher.publishOnboardingState("IN_PROGRESS", "user-42", "app-7", null, "ama.serwaa");

        assertThat(capturePayload()).doesNotContainKey("authority").containsEntry("state", "IN_PROGRESS");
    }

    /** Keyed on {@code accountId}, so this frame orders against the gateway's account events. */
    @Test
    void isKeyedOnTheAccountId() {
        publisher.publishOnboardingState("ACTIVE", "user-42", "app-7", "ROLE_NURSE", "ama.serwaa");

        assertThat(captureMessage().getHeaders().get(KafkaHeaders.KEY)).isEqualTo("user-42".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code application.kafka.enabled=false} short-circuits <b>before</b> {@code streamBridge.send},
     * so no binding is created and {@code BindingService} never enters its retry loop. Asserted here
     * as well as on the other publishers because the rename touched this method's body.
     */
    @Test
    void publishesNothingWhenKafkaIsDisabled() {
        ApplicationProperties disabled = new ApplicationProperties();
        disabled.getKafka().setEnabled(false);
        new DomainEventPublisher(streamBridge, disabled).publishOnboardingState(
            "COMPLETED",
            "user-42",
            "app-7",
            "ROLE_NURSE",
            "ama.serwaa"
        );

        verify(streamBridge, never()).send(any(String.class), any(Message.class));
    }

    @SuppressWarnings("unchecked")
    private Message<DomainEventEnvelope> captureMessage() {
        ArgumentCaptor<Message<DomainEventEnvelope>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq(DomainEventPublisher.ONBOARDING_STATE_BINDING), captor.capture());
        return captor.getValue();
    }

    private DomainEventEnvelope captureEnvelope() {
        return captureMessage().getPayload();
    }

    private Map<String, Object> capturePayload() {
        return captureEnvelope().payload();
    }
}
