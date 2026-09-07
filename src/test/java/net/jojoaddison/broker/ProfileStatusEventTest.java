package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import net.jojoaddison.config.ApplicationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/**
 * backlog.md item 47, phase 2: {@code ProfileStatus} carries the seven fields the contract names, in
 * the estate's envelope, on the topic hc-admin is bound to — and carries nothing else.
 *
 * <p>The field names are the assertion, because they are the whole of what this service and its
 * consumers share: a rename here is a silent decorrelation on the far side rather than a compile
 * error on this one. The absences are asserted just as deliberately. A licence number, a role and a
 * name are the three things somebody enriching this event would reach for; the first two are settled
 * as never travelling, and none of them may go on a topic four stacks can read.
 */
class ProfileStatusEventTest {

    private static final Instant CREATED = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-09-07T11:30:00Z");

    private StreamBridge streamBridge;
    private DomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        streamBridge = mock(StreamBridge.class);
        publisher = new DomainEventPublisher(streamBridge, new ApplicationProperties());
    }

    @Test
    void publishesTheSevenContractedFieldsAndNoOthers() {
        publish();

        ProfessionalEvent event = capture();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.type()).isEqualTo("ProfileStatus");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.source()).isEqualTo("hc-professional-service");
        assertThat(event.data()).containsOnlyKeys(
            "profileId",
            "accountId",
            "isComplete",
            "isVerified",
            "createdDate",
            "modifiedDate",
            "lastModifiedBy"
        );
        assertThat(event.data())
            .containsEntry("profileId", "profile-7")
            .containsEntry("accountId", "ama.serwaa")
            .containsEntry("isComplete", true)
            .containsEntry("isVerified", false)
            .containsEntry("createdDate", CREATED)
            .containsEntry("modifiedDate", MODIFIED)
            .containsEntry("lastModifiedBy", "ama.serwaa");
    }

    /**
     * The binding is {@code onboardingStateEvents-out-0}, which resolves to
     * {@code hc.professional.registration}. Publishing this to {@code hc.professional.entity}
     * instead would put it where nothing on hc-admin is listening — that is the failure being
     * avoided, not a preference between two topics.
     */
    @Test
    void ridesTheRegistrationTopicKeyedOnTheAccountId() {
        publish();

        ArgumentCaptor<Message<ProfessionalEvent>> captor = captor();
        verify(streamBridge).send(eq(DomainEventPublisher.ONBOARDING_STATE_BINDING), captor.capture());
        assertThat(new String((byte[]) captor.getValue().getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8)).isEqualTo(
            "ama.serwaa"
        );
    }

    /**
     * The envelope names the same subject the payload does, so the two cannot drift — this is the
     * identifier hc-admin joins the account half on. {@code email} and {@code login} are null: the
     * first by the payload rule, the second because it is the same string here and one value under
     * two names is something a consumer eventually disagrees with itself about.
     */
    @Test
    void theSubjectCarriesTheSameAccountIdAsThePayload() {
        publish();

        ProfessionalEvent event = capture();
        assertThat(event.subject()).isEqualTo(new ProfessionalEvent.Subject(null, null, "ama.serwaa"));
        assertThat(event.subject().accountId()).isEqualTo(event.data().get("accountId"));
    }

    @Test
    void carriesNoRoleNoLicenceAndNoName() {
        publish();

        assertThat(capture().data()).doesNotContainKeys(
            "role",
            "requestedRole",
            "licenceNumber",
            "licenseNumber",
            "licenceVerified",
            "firstName",
            "lastName",
            "name",
            "email",
            "mobilePhone"
        );
    }

    /**
     * Null on every profile written before the audit fields existed — Mongo has no migration
     * framework here and inventing a creation date is worse than admitting there is none.
     */
    @Test
    void toleratesAProfileWithNoAuditDatesYet() {
        publisher.publishProfileStatus("ama.serwaa", "profile-7", false, false, null, null, null);

        assertThat(capture().data())
            .containsEntry("createdDate", null)
            .containsEntry("modifiedDate", null)
            .containsEntry("lastModifiedBy", null);
    }

    /**
     * A deployment with no broker is a supported configuration rather than a failure: nothing is
     * sent, so no binding is created and {@code BindingService} never enters its retry loop.
     */
    @Test
    void sendsNothingWhenPublishingIsDisabled() {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getKafka().setEnabled(false);

        new DomainEventPublisher(streamBridge, properties).publishProfileStatus(
            "ama.serwaa",
            "profile-7",
            true,
            false,
            CREATED,
            MODIFIED,
            "ama.serwaa"
        );

        verify(streamBridge, never()).send(anyString(), any());
    }

    @Test
    void neverPropagatesBrokerFailures() {
        when(streamBridge.send(eq(DomainEventPublisher.ONBOARDING_STATE_BINDING), any(Message.class))).thenThrow(
            new IllegalStateException("broker down")
        );

        publish();
        // no exception — saving a profile or verifying a document must not fail because Kafka is
        // unavailable
    }

    private void publish() {
        publisher.publishProfileStatus("ama.serwaa", "profile-7", true, false, CREATED, MODIFIED, "ama.serwaa");
    }

    private ProfessionalEvent capture() {
        ArgumentCaptor<Message<ProfessionalEvent>> captor = captor();
        verify(streamBridge).send(eq(DomainEventPublisher.ONBOARDING_STATE_BINDING), captor.capture());
        return captor.getValue().getPayload();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Message<ProfessionalEvent>> captor() {
        return ArgumentCaptor.forClass(Message.class);
    }
}
