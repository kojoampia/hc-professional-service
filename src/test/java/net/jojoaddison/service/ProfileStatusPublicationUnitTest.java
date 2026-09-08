package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * backlog.md item 47, phase 2: <b>the announcement has to happen on every path that changes what it
 * reports, not only on the one that creates the profile.</b>
 *
 * <p>{@code ProfileStatusEventTest} proves the frame is the right shape. This proves the call sites
 * exist, which is the half a shape test cannot see — a record hc-admin can never refresh is the
 * defect being fixed rather than a fix, and the two paths most easily forgotten are the ones here:
 * an <em>update</em> to an existing profile, and a document decision that moves {@code isVerified}
 * without touching the profile row at all.
 */
class ProfileStatusPublicationUnitTest {

    private static final Instant CREATED = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-09-07T11:30:00Z");

    private ProfessionalApplicationRepository applicationRepository;
    private ProfileRepository profileRepository;
    private PersonalDocumentRepository personalDocumentRepository;
    private DomainEventPublisher events;
    private OnboardingService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ProfessionalApplicationRepository.class);
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

        when(applicationRepository.findByAccountId(anyString())).thenReturn(Optional.empty());
        when(personalDocumentRepository.findByProfileId(anyString())).thenReturn(List.of());
    }

    /**
     * The path an applicant's second save takes. {@code entity.created} beside it fires only on the
     * first, deliberately — this must fire on both, or the far side shows the profile as it was on
     * day one for ever.
     */
    @Test
    void updatingAnExistingProfileAnnouncesItsStatus() {
        Profile stored = existingProfile();
        when(profileRepository.findByAccountId("ama.serwaa")).thenReturn(Optional.of(stored));
        when(profileRepository.save(any(Profile.class))).thenReturn(stored);

        service.upsertOwnProfile("ama.serwaa", new Profile().firstName("Ama"));

        verify(events).publishProfileStatus("user-42", "profile-7", false, false, CREATED, MODIFIED, "ama.serwaa");
        // and NOT entity.created, which is a creation event and this was not one
        verify(events, org.mockito.Mockito.never()).publishEntityCreated(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * {@code isVerified} moves here and the {@code Profile} row is not written at all, so an
     * announcement hung off the profile save alone would never fire for the moment a clinician is
     * actually cleared.
     */
    @Test
    void verifyingADocumentAnnouncesTheProfileItBelongsTo() {
        PersonalDocument document = new PersonalDocument().profileId("profile-7").type(DocumentType.LICENSE);
        document.setId("doc-1");
        when(personalDocumentRepository.findById("doc-1")).thenReturn(Optional.of(document));
        when(personalDocumentRepository.save(any(PersonalDocument.class))).thenReturn(document);
        when(personalDocumentRepository.findByProfileId("profile-7")).thenReturn(List.of(document));
        when(profileRepository.findById("profile-7")).thenReturn(Optional.of(existingProfile()));
        when(profileRepository.findByAccountId("ama.serwaa")).thenReturn(Optional.of(existingProfile()));

        service.verifyDocument("doc-1", "admin");

        // isVerified true: the one live document on the profile is now VERIFIED
        verify(events).publishProfileStatus(eq("user-42"), eq("profile-7"), eq(false), eq(true), any(), any(), anyString());
    }

    /** The mirror of the above: a rejection takes {@code isVerified} back to false and must say so. */
    @Test
    void rejectingADocumentAnnouncesTheProfileItBelongsTo() {
        PersonalDocument document = new PersonalDocument().profileId("profile-7").type(DocumentType.LICENSE);
        document.setId("doc-1");
        document.setVerificationStatus(VerificationStatus.VERIFIED);
        when(personalDocumentRepository.findById("doc-1")).thenReturn(Optional.of(document));
        when(personalDocumentRepository.save(any(PersonalDocument.class))).thenReturn(document);
        when(personalDocumentRepository.findByProfileId("profile-7")).thenReturn(List.of(document));
        when(profileRepository.findById("profile-7")).thenReturn(Optional.of(existingProfile()));
        when(profileRepository.findByAccountId("ama.serwaa")).thenReturn(Optional.of(existingProfile()));

        service.rejectDocument("doc-1", "illegible", "admin");

        verify(events).publishProfileStatus(eq("user-42"), eq("profile-7"), eq(false), eq(false), any(), any(), anyString());
    }

    /**
     * The write path is the profile, not the event. Everything the announcement does — two
     * repository reads and a send — is guarded, because the publisher can only catch its own broker
     * failures and not a repository throwing on the way in.
     */
    @Test
    void aFailedAnnouncementDoesNotFailTheWrite() {
        Profile stored = existingProfile();
        when(profileRepository.findByAccountId("ama.serwaa")).thenReturn(Optional.of(stored));
        when(profileRepository.save(any(Profile.class))).thenReturn(stored);
        doThrow(new IllegalStateException("broker down"))
            .when(events)
            .publishProfileStatus(any(), anyString(), anyBoolean(), anyBoolean(), any(), any(), any());

        assertThat(service.upsertOwnProfile("ama.serwaa", new Profile().firstName("Ama"))).isSameAs(stored);
    }

    /**
     * A profile with no account cannot be correlated with anything on the far side. Reachable rather
     * than defensive: {@code ProfileResource} accepts a client-built {@code Profile}.
     */
    @Test
    void aProfileWithNoAccountAnnouncesNothing() {
        service.publishProfileStatus(new Profile().firstName("Ama"));

        verify(events, org.mockito.Mockito.never()).publishProfileStatus(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
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
