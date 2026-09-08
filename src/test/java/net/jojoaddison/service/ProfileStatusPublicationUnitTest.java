package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeDeleteEvent;

/**
 * {@link ProfileStatusAnnouncer}: <b>the announcement follows the write, not the caller</b>
 * (backlog.md item 49).
 *
 * <p>This class used to assert that four named methods on {@link OnboardingService} and
 * {@code ProfileResource} each called the publisher — the call sites that existed, never the ones
 * that were missing — and it was green throughout the whole life of the defect it was written to
 * cover. Two of the paths that move {@code isVerified} were simply not on the list, and no assertion
 * of that shape can notice a third.
 *
 * <p>So the subject is now the mechanism: given a persisted {@link Profile}, {@link PersonalDocument}
 * or {@link ProfessionalApplication}, is a frame composed, is it composed <em>once</em> per request,
 * and does a failure stay inside the announcement. Which handler did the saving is not a fact any of
 * these tests knows, which is the point. {@code ProfileStatusOnEveryWriteIT} is the other half —
 * that the real HTTP surfaces do reach this.
 */
class ProfileStatusPublicationUnitTest {

    private static final Instant CREATED = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-09-07T11:30:00Z");

    private ProfileRepository profileRepository;
    private PersonalDocumentRepository personalDocumentRepository;
    private DomainEventPublisher events;
    private OnboardingService service;
    private ProfileStatusAnnouncer announcer;

    @BeforeEach
    void setUp() {
        ProfessionalApplicationRepository applicationRepository = mock(ProfessionalApplicationRepository.class);
        profileRepository = mock(ProfileRepository.class);
        personalDocumentRepository = mock(PersonalDocumentRepository.class);
        events = mock(DomainEventPublisher.class);
        service = new OnboardingService(
            applicationRepository,
            mock(OnboardingEventRepository.class),
            profileRepository,
            personalDocumentRepository,
            events
        );
        announcer = new ProfileStatusAnnouncer(profileRepository, personalDocumentRepository, service);

        when(applicationRepository.findByAccountId(anyString())).thenReturn(Optional.empty());
        when(personalDocumentRepository.findByProfileId(anyString())).thenReturn(List.of());
        when(profileRepository.findById("profile-7")).thenReturn(Optional.of(existingProfile()));
        when(profileRepository.findByAccountId("ama.serwaa")).thenReturn(Optional.of(existingProfile()));
    }

    @AfterEach
    void closeAnyOpenRequest() {
        announcer.flush();
    }

    /**
     * The applicant's own save, a staff save through the entity surface, a preferences toggle that
     * creates the row — one event, because they are one event. The announcer never learns which.
     */
    @Test
    void savingAProfileAnnouncesItsStatus() {
        inOneRequest(() -> announcer.onAfterSave(saved(existingProfile())));

        verify(events).publishProfileStatus("user-42", "profile-7", false, false, CREATED, MODIFIED, "ama.serwaa");
    }

    /**
     * <b>The defect item 49 was opened for.</b> An upload adds a {@code PENDING} row and writes
     * nothing to the profile, so an announcement hung off the profile save could not see it — and the
     * upload path was not on the list of paths that announced. Here the save is the whole trigger, so
     * whether it came from a renewal, a reviewer's verdict or the CRUD surface does not arise.
     */
    @Test
    void savingADocumentAnnouncesTheProfileItBelongsTo() {
        PersonalDocument uploaded = new PersonalDocument()
            .profileId("profile-7")
            .type(DocumentType.LICENSE)
            .verificationStatus(VerificationStatus.PENDING);
        when(personalDocumentRepository.findByProfileId("profile-7")).thenReturn(List.of(uploaded));

        inOneRequest(() -> announcer.onAfterSave(saved(uploaded)));

        verify(events).publishProfileStatus(eq("user-42"), eq("profile-7"), eq(false), eq(false), any(), any(), anyString());
    }

    /** Verified rather than pending: the same trigger, reporting the other verdict. */
    @Test
    void aVerifiedDocumentIsAnnouncedAsVerified() {
        PersonalDocument verified = new PersonalDocument()
            .profileId("profile-7")
            .type(DocumentType.LICENSE)
            .verificationStatus(VerificationStatus.VERIFIED);
        when(personalDocumentRepository.findByProfileId("profile-7")).thenReturn(List.of(verified));

        inOneRequest(() -> announcer.onAfterSave(saved(verified)));

        verify(events).publishProfileStatus(eq("user-42"), eq("profile-7"), eq(false), eq(true), any(), any(), anyString());
    }

    /**
     * Deleting the last document takes {@code isVerified} back to false. Resolved before the delete,
     * because afterwards there is no row left to read the profile id off.
     */
    @Test
    void deletingADocumentAnnouncesTheProfileItBelongedTo() {
        PersonalDocument doomed = new PersonalDocument().profileId("profile-7").type(DocumentType.LICENSE);
        doomed.setId("doc-1");
        when(personalDocumentRepository.findById("doc-1")).thenReturn(Optional.of(doomed));

        inOneRequest(() -> announcer.onBeforeDelete(deletionOf(PersonalDocument.class, "doc-1")));

        verify(events).publishProfileStatus(eq("user-42"), eq("profile-7"), eq(false), eq(false), any(), any(), anyString());
    }

    /**
     * The third document type, and the one easiest to miss: {@code isComplete} counts eight
     * requirements and the first of them, consent, lives on the application rather than on the
     * profile or on any document.
     */
    @Test
    void savingAnApplicationAnnouncesTheProfileItsConsentCounts() {
        ProfessionalApplication application = new ProfessionalApplication()
            .accountId("ama.serwaa")
            .profileId("profile-7")
            .status(OnboardingStatus.APPLICATION_STARTED)
            .consentAcceptedAt(Instant.now());

        inOneRequest(() -> announcer.onAfterSave(saved(application)));

        verify(events).publishProfileStatus(eq("user-42"), eq("profile-7"), anyBoolean(), anyBoolean(), any(), any(), anyString());
    }

    /**
     * A superseding upload saves twice and an organisation assignment saves two different documents;
     * neither is two decisions. One frame per request, and it is composed after the last write rather
     * than between them.
     */
    @Test
    void manySavesInOneRequestAnnounceOnce() {
        PersonalDocument replacement = new PersonalDocument().profileId("profile-7").type(DocumentType.LICENSE);
        PersonalDocument archived = new PersonalDocument().profileId("profile-7").type(DocumentType.LICENSE);

        inOneRequest(() -> {
            announcer.onAfterSave(saved(replacement));
            announcer.onAfterSave(saved(archived));
            announcer.onAfterSave(saved(existingProfile()));
        });

        verify(events, times(1)).publishProfileStatus(any(), eq("profile-7"), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    /**
     * Off a request thread there is no boundary to defer to — the compliance scheduler, a Kafka
     * consumer, a startup runner. Announcing per save there is the right way round: an extra snapshot
     * is harmless and a missing one is this whole item.
     */
    @Test
    void outsideARequestEachSaveAnnouncesImmediately() {
        announcer.onAfterSave(saved(existingProfile()));
        announcer.onAfterSave(saved(existingProfile()));

        verify(events, times(2)).publishProfileStatus(any(), eq("profile-7"), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    /**
     * The write path is the clinician's upload, not the event. Everything the announcement does — two
     * repository reads and a send — is guarded, because the publisher can only catch its own broker
     * failures and not a repository throwing on the way in.
     */
    @Test
    void aFailedAnnouncementDoesNotFailTheWrite() {
        doThrow(new IllegalStateException("broker down"))
            .when(events)
            .publishProfileStatus(any(), anyString(), anyBoolean(), anyBoolean(), any(), any(), any());

        assertThatCode(() -> inOneRequest(() -> announcer.onAfterSave(saved(existingProfile())))).doesNotThrowAnyException();
    }

    /** A document that names no profile is not a fault and is not an announcement either. */
    @Test
    void aDocumentWithNoProfileAnnouncesNothing() {
        inOneRequest(() -> announcer.onAfterSave(saved(new PersonalDocument().type(DocumentType.LICENSE))));

        verify(events, never()).publishProfileStatus(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    /**
     * A profile with no account cannot be correlated with anything on the far side. Reachable rather
     * than defensive: {@code ProfileResource} accepts a client-built {@code Profile}.
     */
    @Test
    void aProfileWithNoAccountAnnouncesNothing() {
        service.publishProfileStatus(new Profile().firstName("Ama"));

        verify(events, never()).publishProfileStatus(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs the saves inside one unit of work and closes it, which is what
     * {@code ProfileStatusAnnouncementFilter} does around a request — open, let the handler write,
     * flush in a finally block.
     */
    private void inOneRequest(Runnable saves) {
        boolean opened = announcer.open();
        try {
            saves.run();
        } finally {
            if (opened) {
                announcer.flush();
            }
        }
    }

    private static <T> AfterSaveEvent<T> saved(T entity) {
        return new AfterSaveEvent<>(entity, new Document(), "collection");
    }

    /**
     * Raw, because the listener is declared over {@code Object} so that one class covers every
     * document type, while {@code BeforeDeleteEvent}'s constructor ties its parameter to the
     * {@code Class} it is handed. There is no spelling of this that is both generic and true.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static BeforeDeleteEvent<Object> deletionOf(Class<?> type, String id) {
        return new BeforeDeleteEvent(new Document("_id", id), type, "collection");
    }

    private Profile existingProfile() {
        Profile profile = new Profile().accountId("ama.serwaa").accountUid("user-42");
        profile.setId("profile-7");
        profile.setCreatedDate(CREATED);
        profile.setModifiedDate(MODIFIED);
        profile.setLastModifiedBy("ama.serwaa");
        return profile;
    }
}
