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
            // The gateway's User.id, which is what the specified contract means by this name — not
            // the login this database stores under it. Present here because the fixture has learnt one.
            "accountId",
            "isComplete",
            "isVerified",
            "createdDate",
            "modifiedDate",
            "lastModifiedBy"
        );
        assertThat(event.data())
            .containsEntry("profileId", "profile-7")
            .containsEntry("accountId", "user-42")
            .containsEntry("isComplete", true)
            .containsEntry("isVerified", false)
            .containsEntry("createdDate", CREATED)
            .containsEntry("modifiedDate", MODIFIED)
            .containsEntry("lastModifiedBy", "ama.serwaa");
    }

    /**
     * {@code accountId} is the gateway's identifier; {@code subject.login} is this database's.
     *
     * <p>This is the assertion that stops the two being collapsed into one. The specified contract
     * names {@code accountId} and means {@code User.id}; every identifier stored in this service is
     * a login. If a later change ever publishes the login under {@code accountId} again — which is
     * what this code did until 2026-09-08 — the field silently changes meaning for a consumer that
     * keys its directory on {@code User.id}, and it looks correct from here.
     *
     * <p>The login is still published, in {@code subject.login}, and is still the join that works
     * until the {@code uid} claim has outlived a remember-me token.
     */
    @Test
    void theAccountIdIsTheGatewaysIdentifierAndNotTheLogin() {
        publish();

        ProfessionalEvent event = capture();
        assertThat(event.data().get("accountId")).isEqualTo("user-42");
        // "ama.serwaa" is the login the caller was authenticated as, and it is not what correlates.
        // It survives in lastModifiedBy alone — an audit value, not a join — so assert the join keys
        // specifically rather than sweeping the whole frame for the string.
        assertThat(event.subject().accountId()).isEqualTo("user-42");
        assertThat(event.data().get("accountId")).isNotEqualTo("ama.serwaa");
    }

    /**
     * A clinician whose {@code User.id} is not known yet publishes a null {@code accountUid} — and
     * everything else unchanged.
     *
     * <p>This is the ordinary case for a long while rather than an edge one: it is every profile
     * written before 2026-09-07, and every one whose owner has not signed in since, for up to the
     * thirty-day remember-me window. <b>Never a guess and never the login substituted for it</b> —
     * a consumer must be able to tell "not known" from "known to be this".
     *
     * <p><b>The key is OMITTED, not present-and-null</b>, and this test asserts the absence rather
     * than a null. The two ends of item 48 disagreed about this until its review: {@code
     * TokenProvider} omits the {@code uid} claim when blank, arguing that a present-and-empty value
     * is one a reader can compare against stored data and match something, while an absent one is
     * the only unambiguous way to say "not known". The same argument holds on the wire, and for the
     * month after this ships the unknown case is nearly every row — so a present null would be the
     * shape most consumers see first.
     */
    @Test
    void omitsAccountIdEntirelyWhenTheProfileHasNotLearntTheGatewaysIdentifierYet() {
        publisher.publishProfileStatus(null, "profile-7", true, false, CREATED, MODIFIED, "ama.serwaa");

        ProfessionalEvent event = capture();
        assertThat(event.data()).doesNotContainKey("accountId");
        // And the subject cannot stand in for it. There is no second identifier to fall back to —
        // that is the point of the shape, not an omission: an unjoinable frame says so.
        assertThat(event.subject().accountId()).isNull();
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
        assertThat(new String((byte[]) captor.getValue().getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8)).isEqualTo("user-42");
    }

    /**
     * A profile with no {@code accountId} carries no partition key rather than a null one. The
     * difference is not cosmetic: {@code key.getBytes()} on null throws, and the publisher's own
     * catch would log it as "Failed to publish" — a data problem reported as a broker fault, and the
     * frame dropped. It goes out unkeyed and unordered instead, and says so at WARN.
     */
    @Test
    void publishesWithNoPartitionKeyRatherThanFailingWhenTheAccountIdIsUnknown() {
        publisher.publishProfileStatus(null, "profile-7", true, false, CREATED, MODIFIED, "ama.serwaa");

        ArgumentCaptor<Message<ProfessionalEvent>> captor = captor();
        verify(streamBridge).send(eq(DomainEventPublisher.ONBOARDING_STATE_BINDING), captor.capture());
        assertThat(captor.getValue().getHeaders()).doesNotContainKey(KafkaHeaders.KEY);
    }

    /**
     * {@code accountId} is the join and nothing else is. The subject used to carry the login beside
     * it; that was a second correlation key, and hc-admin's {@code SiblingDomainEvent} names only
     * this one — "the correlation key: lowercased email for a patient, accountId for a professional".
     * A consumer offered two keys will eventually join on the wrong one.
     */
    @Test
    void theSubjectCarriesTheGatewaysIdentifierAndNoSecondJoinKey() {
        publish();

        ProfessionalEvent event = capture();
        assertThat(event.subject()).isEqualTo(new ProfessionalEvent.Subject(null, "user-42"));
        assertThat(event.subject().accountId()).isEqualTo(event.data().get("accountId"));
        // No email either: this half carries identifiers, and the account half carries the person.
        assertThat(event.subject().email()).isNull();
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
        publisher.publishProfileStatus("user-42", "profile-7", false, false, null, null, null);

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
            "user-42",
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
        publisher.publishProfileStatus("user-42", "profile-7", true, false, CREATED, MODIFIED, "ama.serwaa");
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
