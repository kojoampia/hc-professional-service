package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import net.jojoaddison.broker.AccountDetailsEvent;
import net.jojoaddison.broker.EntityChangeEvent;
import net.jojoaddison.broker.ProfessionalEventType;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.service.dto.OnboardingProgressDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code MeterConsumer}'s decisions, one at a time — backlog.md row 230, unit A, decision 2.
 *
 * <p>A unit test rather than an IT because what is being asserted is the <b>control flow over the
 * answers a frame can carry</b>, and most of those answers are states a broker in a test cannot
 * conveniently produce: a redelivery, a frame naming nobody, a frame for a type nobody meters, a
 * repository that throws. {@code OnboardingProgressStreamIT} and {@code OnboardingProgressIT} cover
 * the other half — that the push and the read are the same number.
 *
 * <p>⚠ <b>Every case asserts about a <em>decision</em>, not about an aggregate.</b> This repository's
 * recorded failure mode for consumer-shaped code is a decision taken on an answer that never arrived
 * — {@code quality/}'s items 78, 83, 87 and 91 are four instances of it — so "it did not crash" is
 * never the assertion.
 */
class MeterConsumerUnitTest {

    private static final String ACCOUNT = "uid-ama";
    private static final String PROFILE_ID = "profile-1";

    private OnboardingService onboardingService;
    private OnboardingProgressStream stream;
    private ProfileRepository profileRepository;
    private PersonalDocumentRepository personalDocumentRepository;
    private ProfessionalApplicationRepository applicationRepository;
    private MeterConsumer consumer;

    @BeforeEach
    void setUp() {
        onboardingService = mock(OnboardingService.class);
        stream = mock(OnboardingProgressStream.class);
        profileRepository = mock(ProfileRepository.class);
        personalDocumentRepository = mock(PersonalDocumentRepository.class);
        applicationRepository = mock(ProfessionalApplicationRepository.class);
        consumer = new MeterConsumer(onboardingService, stream, profileRepository, personalDocumentRepository, applicationRepository);
        // Somebody is watching, in every case but the one that asserts otherwise.
        when(stream.openStreams(anyString())).thenReturn(1);
        when(onboardingService.progressFor(anyString())).thenReturn(aMeter());
        when(onboardingService.recordAccountCompleteness(anyString(), anyBoolean(), any())).thenReturn(true);
    }

    // --- the gateway's step-1 verdict --------------------------------------------------------------

    @Test
    void recordsTheGatewaysVerdictAndPushesTheRecomputedMeter() {
        consumer.onAccountDetails(accountDetails("e-1", ACCOUNT, true, Instant.parse("2026-10-10T09:00:00Z")));

        verify(onboardingService).recordAccountCompleteness(ACCOUNT, true, Instant.parse("2026-10-10T09:00:00Z"));
        verify(stream).push(eq(ACCOUNT), any(OnboardingProgressDTO.class));
    }

    @Test
    void recordsAnOutstandingStepOneToo() {
        consumer.onAccountDetails(accountDetails("e-1", ACCOUNT, false, Instant.now()));

        verify(onboardingService).recordAccountCompleteness(eq(ACCOUNT), eq(false), any());
    }

    /**
     * ⛔ <b>The topic is shared and carries six types in two envelope shapes.</b> A
     * {@code DomainEventEnvelope}-shaped frame deserialises into this record with a {@code null}
     * {@code type}, which is exactly how the estate's "dispatch on which of {@code type} and
     * {@code eventType} is present" rule is implemented on this side.
     */
    @Test
    void ignoresEveryOtherTypeOnTheRegistrationTopic() {
        consumer.onAccountDetails(
            new AccountDetailsEvent(
                "e-1",
                ProfessionalEventType.ACCOUNT_CREATED,
                Instant.now(),
                new AccountDetailsEvent.Subject(ACCOUNT),
                Map.of("username", "ama")
            )
        );
        // The other envelope shape: no `type` at all.
        consumer.onAccountDetails(new AccountDetailsEvent("e-2", null, Instant.now(), new AccountDetailsEvent.Subject(ACCOUNT), Map.of()));

        verify(onboardingService, never()).recordAccountCompleteness(anyString(), anyBoolean(), any());
        verify(stream, never()).push(anyString(), any());
    }

    /**
     * ⛔ <b>A frame with no verdict is unreadable, not "incomplete".</b> Applying a missing or
     * non-boolean {@code detailsComplete} as {@code false} would move a clinician's step 1 backwards
     * and leave it there until their account was next written — a decision taken on an answer that
     * never arrived, which is the failure this estate keeps paying for.
     */
    @Test
    void refusesAFrameWhoseVerdictIsMissingOrNotABoolean() {
        consumer.onAccountDetails(
            new AccountDetailsEvent(
                "e-1",
                ProfessionalEventType.ACCOUNT_DETAILS_UPDATED,
                Instant.now(),
                new AccountDetailsEvent.Subject(ACCOUNT),
                Map.of()
            )
        );
        consumer.onAccountDetails(
            new AccountDetailsEvent(
                "e-2",
                ProfessionalEventType.ACCOUNT_DETAILS_UPDATED,
                Instant.now(),
                new AccountDetailsEvent.Subject(ACCOUNT),
                Map.of("detailsComplete", "true")
            )
        );
        consumer.onAccountDetails(
            new AccountDetailsEvent(
                "e-3",
                ProfessionalEventType.ACCOUNT_DETAILS_UPDATED,
                Instant.now(),
                null,
                Map.of("detailsComplete", true)
            )
        );

        verify(onboardingService, never()).recordAccountCompleteness(anyString(), anyBoolean(), any());
    }

    /**
     * ⚠ <b>Nothing is pushed when the projection did not move.</b>
     * {@code recordAccountCompleteness} refuses a frame older than the stored observation, and a push
     * after a refused frame would send a meter identical to the last one — turning a redelivery storm
     * into a socket-write storm for no information.
     */
    @Test
    void doesNotPushWhenTheProjectionRefusedTheObservation() {
        when(onboardingService.recordAccountCompleteness(anyString(), anyBoolean(), any())).thenReturn(false);

        consumer.onAccountDetails(accountDetails("e-1", ACCOUNT, true, Instant.now()));

        verify(stream, never()).push(anyString(), any());
    }

    // --- this service's own changes ----------------------------------------------------------------

    @Test
    void resolvesAProfileChangeToItsOwnAccount() {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(new Profile().accountId(ACCOUNT)));

        consumer.onEntityChange(entityChange("e-1", "Profile", PROFILE_ID));

        verify(stream).push(eq(ACCOUNT), any());
    }

    /** Two hops: the document names a profile, the profile names the account. */
    @Test
    void resolvesADocumentChangeThroughItsProfile() {
        when(personalDocumentRepository.findById("doc-1")).thenReturn(Optional.of(new PersonalDocument().profileId(PROFILE_ID)));
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(new Profile().accountId(ACCOUNT)));

        consumer.onEntityChange(entityChange("e-1", "PersonalDocument", "doc-1"));

        verify(stream).push(eq(ACCOUNT), any());
    }

    @Test
    void resolvesAnApplicationChangeToItsOwnAccount() {
        when(applicationRepository.findById("app-1")).thenReturn(
            Optional.of(new ProfessionalApplication().accountId(ACCOUNT).status(ProfileStatus.APPLICATION_STARTED))
        );

        consumer.onEntityChange(entityChange("e-1", "ProfessionalApplication", "app-1"));

        verify(stream).push(eq(ACCOUNT), any());
    }

    /**
     * ⚠ <b>{@code professional.event} carries every collection in this service</b> — tasks, teams,
     * duty rosters, categories, reports. Recomputing a meter for each would be four repository reads
     * per write in the deployment for a number that cannot have changed, so the list is positive and
     * a frame outside it is dropped without a lookup.
     */
    @Test
    void ignoresEntityTypesThatCannotMoveTheMeter() {
        consumer.onEntityChange(entityChange("e-1", "DutyRoster", "roster-1"));
        consumer.onEntityChange(entityChange("e-2", "Team", "team-1"));
        consumer.onEntityChange(entityChange("e-3", "AccountCompleteness", ACCOUNT));

        verify(stream, never()).push(anyString(), any());
        verify(profileRepository, never()).findById(anyString());
    }

    /**
     * ⚠ <b>The account is resolved from the ROW, never from {@code data.actorAccountId}.</b> The actor
     * is whoever made the write — a reviewer verifying a document, an administrator editing a profile
     * — so keying on it would refresh a stranger's stream and refresh nothing for the clinician whose
     * meter moved.
     */
    @Test
    void resolvesTheSubjectAndNotTheActor() {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(new Profile().accountId(ACCOUNT)));

        consumer.onEntityChange(
            new EntityChangeEvent(
                "e-1",
                ProfessionalEventType.ENTITY_CHANGED,
                EntityChangeEvent.VERSION,
                Instant.now(),
                "hc-professional-service",
                new EntityChangeEvent.Subject("Profile", PROFILE_ID),
                Map.of("action", "UPDATED", "actorAccountId", "uid-a-reviewer")
            )
        );

        verify(stream).push(eq(ACCOUNT), any());
        verify(stream, never()).push(eq("uid-a-reviewer"), any());
    }

    /**
     * A row that is gone cannot be resolved — a {@code DELETED} frame for a metered type. Accepted
     * rather than worked around: the next {@code GET} is correct because the meter is computed from
     * the database, so what is lost is the live refresh.
     */
    @Test
    void dropsAChangeWhoseRowNoLongerExists() {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());

        consumer.onEntityChange(entityChange("e-1", "Profile", PROFILE_ID));

        verify(stream, never()).push(anyString(), any());
    }

    // --- idempotence and failure containment -------------------------------------------------------

    /**
     * ⛔ <b>Dedupe on {@code eventId}, both ways in.</b> Delivery is at-least-once, so the same frame
     * arrives more than once; a replayed frame must not move a step twice.
     *
     * <p>⚠ What makes "twice" observable at all is that this asserts the <em>number of applications</em>
     * rather than the resulting value: recomputing is idempotent, so a double-applied frame produces
     * the same meter and an aggregate assertion could not tell the two apart.
     */
    @Test
    void appliesEachEventIdOnceOnBothBindings() {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(new Profile().accountId(ACCOUNT)));

        consumer.onAccountDetails(accountDetails("account-1", ACCOUNT, true, Instant.now()));
        consumer.onAccountDetails(accountDetails("account-1", ACCOUNT, true, Instant.now()));
        consumer.onAccountDetails(accountDetails("account-1", ACCOUNT, false, Instant.now()));

        verify(onboardingService, times(1)).recordAccountCompleteness(anyString(), anyBoolean(), any());

        consumer.onEntityChange(entityChange("entity-1", "Profile", PROFILE_ID));
        consumer.onEntityChange(entityChange("entity-1", "Profile", PROFILE_ID));

        verify(stream, times(2)).push(eq(ACCOUNT), any());
    }

    /**
     * A frame with no {@code eventId} is applied rather than dropped: recomputing is idempotent, so a
     * duplicate costs four reads and a socket write, where dropping it would cost a refresh for no
     * benefit.
     */
    @Test
    void appliesAFrameThatCarriesNoEventId() {
        consumer.onAccountDetails(accountDetails(null, ACCOUNT, true, Instant.now()));
        consumer.onAccountDetails(accountDetails(null, ACCOUNT, true, Instant.now()));

        verify(onboardingService, times(2)).recordAccountCompleteness(anyString(), anyBoolean(), any());
    }

    /**
     * ⛔ <b>Nothing propagates.</b> A consumer that throws costs the binding — the frame is retried
     * and the container may stop the container — and the push notifications on this service share the
     * broker with it. Both publishers here already catch rather than propagate; the same rule runs in
     * this direction.
     */
    @Test
    void neverPropagatesAFailureIntoTheBinding() {
        when(profileRepository.findById(PROFILE_ID)).thenThrow(new IllegalStateException("mongo down"));
        when(onboardingService.recordAccountCompleteness(anyString(), anyBoolean(), any())).thenThrow(
            new IllegalStateException("mongo down")
        );

        assertThatCode(() -> consumer.onEntityChange(entityChange("e-1", "Profile", PROFILE_ID))).doesNotThrowAnyException();
        assertThatCode(() -> consumer.onAccountDetails(accountDetails("e-2", ACCOUNT, true, Instant.now()))).doesNotThrowAnyException();
    }

    @Test
    void toleratesANullFrameOnEitherBinding() {
        assertThatCode(() -> consumer.onAccountDetails(null)).doesNotThrowAnyException();
        assertThatCode(() -> consumer.onEntityChange(null)).doesNotThrowAnyException();
    }

    /**
     * Nobody is watching, so nothing is computed. An event arrives for every applicant whether or not
     * they have the page open, and the meter is four repository reads.
     */
    @Test
    void doesNotComputeAMeterWhenNoStreamIsOpen() {
        when(stream.openStreams(ACCOUNT)).thenReturn(0);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(new Profile().accountId(ACCOUNT)));

        consumer.onEntityChange(entityChange("e-1", "Profile", PROFILE_ID));

        verify(onboardingService, never()).progressFor(anyString());
        verify(stream, never()).push(anyString(), any());
    }

    /**
     * ⚠ The metered set names the three collections behind steps 2, 3 and 4 by their simple names,
     * which is what {@code EntityChangeEvent.Subject.entityType} carries — never a collection name and
     * never a fully-qualified class.
     *
     * <p>{@code Address} and {@code EmergencyContact} are deliberately absent: both are embedded in the
     * {@code Profile} document, so a change to either <em>is</em> a {@code Profile} save and arrives
     * under that name. An entry for them would match nothing.
     */
    @Test
    void theMeteredTypesAreTheThreeCollectionsBehindStepsTwoThreeAndFour() {
        assertThat(MeterConsumer.METERED_ENTITY_TYPES).containsExactlyInAnyOrder("Profile", "PersonalDocument", "ProfessionalApplication");
    }

    private AccountDetailsEvent accountDetails(String eventId, String accountId, boolean complete, Instant occurredAt) {
        return new AccountDetailsEvent(
            eventId,
            ProfessionalEventType.ACCOUNT_DETAILS_UPDATED,
            occurredAt,
            new AccountDetailsEvent.Subject(accountId),
            Map.of(AccountDetailsEvent.DETAILS_COMPLETE, complete)
        );
    }

    private EntityChangeEvent entityChange(String eventId, String entityType, String entityId) {
        return new EntityChangeEvent(
            eventId,
            ProfessionalEventType.ENTITY_CHANGED,
            EntityChangeEvent.VERSION,
            Instant.now(),
            "hc-professional-service",
            new EntityChangeEvent.Subject(entityType, entityId),
            Map.of("action", "UPDATED")
        );
    }

    private OnboardingProgressDTO aMeter() {
        return new OnboardingProgressDTO(
            22,
            false,
            ProfileStatus.APPLICATION_STARTED,
            java.util.List.of(new OnboardingProgressDTO.Requirement("consent", true)),
            new OnboardingProgressDTO.Steps(false, false, false, true)
        );
    }
}
