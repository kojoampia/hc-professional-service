package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The {@code uid} claim cutover (backlog.md item 48), from {@code api/}'s side.
 *
 * <p>The gateway started stamping {@code uid} on 2026-09-07. A remember-me token lives thirty days,
 * so for a month afterwards <b>most tokens arriving here carry no such claim</b> — and after that,
 * any token minted by hc-admin or hc-patient still will not, because they share this stack's signing
 * key and not its user store. The claim is therefore optional for ever, not just for a window, and
 * these are the four ways that has to behave.
 *
 * <p>Each case is exercised through {@code upsertOwnProfile} against a real {@link SecurityContextHolder},
 * because the claim is read from the security context and a test that passed the value in as an
 * argument would prove nothing about the wiring that actually reads it.
 */
class AccountUidCutoverUnitTest {

    private static final String LOGIN = "ama.serwaa";
    private static final String UID = "68bd4e2a91c30d5f7a1e4c02";

    private ProfileRepository profileRepository;
    private DomainEventPublisher events;
    private OnboardingService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        events = mock(DomainEventPublisher.class);
        ProfessionalApplicationRepository applicationRepository = mock(ProfessionalApplicationRepository.class);
        PersonalDocumentRepository personalDocumentRepository = mock(PersonalDocumentRepository.class);
        service = new OnboardingService(
            applicationRepository,
            mock(OnboardingEventRepository.class),
            profileRepository,
            personalDocumentRepository,
            events
        );

        when(applicationRepository.findByAccountId(anyString())).thenReturn(Optional.empty());
        when(personalDocumentRepository.findByProfileId(anyString())).thenReturn(List.of());
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** The forward path: a token minted after the cutover teaches the profile its gateway id. */
    @Test
    void aTokenCarryingTheClaimStampsItOnTheProfile() {
        authenticateWith(SecurityUtils.MINTING_ISSUER, UID);
        when(profileRepository.findByAccountId(LOGIN)).thenReturn(Optional.empty());

        Profile saved = service.upsertOwnProfile(LOGIN, new Profile().firstName("Ama"));

        assertThat(saved.getAccountUid()).isEqualTo(UID);
        assertThat(saved.getAccountId()).isEqualTo(LOGIN);
    }

    /**
     * <b>The pre-claim token path.</b> A token minted before 2026-09-07 has no {@code uid} at all.
     *
     * <p>It must resolve to "not known" — not to an exception, not to a 401, and above all not to
     * the login substituted for a {@code User.id}, which would look right on the wire and be wrong.
     * The write itself succeeds exactly as it did before the claim existed, which is the property
     * that lets the gateway change ship on its own.
     */
    @Test
    void aTokenMintedBeforeTheClaimExistedWritesTheProfileAndLearnsNoUid() {
        authenticateWithoutUidClaim();
        when(profileRepository.findByAccountId(LOGIN)).thenReturn(Optional.empty());

        Profile saved = service.upsertOwnProfile(LOGIN, new Profile().firstName("Ama"));

        assertThat(SecurityUtils.getCurrentUserAccountUid()).isEmpty();
        assertThat(saved.getAccountUid()).isNull();
        assertThat(saved.getAccountId()).isEqualTo(LOGIN);
        assertThat(saved.getFirstName()).isEqualTo("Ama");
    }

    /**
     * <b>A pre-claim token must not undo the cutover.</b>
     *
     * <p>This is the case that makes the cutover monotonic. A clinician who signs in on the web,
     * learning their uid, and then saves from a phone still holding a thirty-day token from before
     * the claim would — if absence were treated as "clear it" — take the join back down again, and
     * the far side would see it flip between known and unknown with nothing in the logs. Absence
     * means "not known", so the stored value stands.
     */
    @Test
    void aPreClaimTokenDoesNotClearAUidTheProfileAlreadyLearnt() {
        authenticateWithoutUidClaim();
        Profile stored = new Profile().accountId(LOGIN).accountUid(UID);
        stored.setId("profile-7");
        when(profileRepository.findByAccountId(LOGIN)).thenReturn(Optional.of(stored));

        Profile saved = service.upsertOwnProfile(LOGIN, new Profile().firstName("Ama"));

        assertThat(saved.getAccountUid()).isEqualTo(UID);
    }

    /**
     * A sibling stack's {@code uid} is discarded, because it names a row in a different user store.
     *
     * <p>hc-admin and hc-patient sign with the same key and {@code TokenOriginValidator} is off, so
     * their tokens reach this code today. Trusting one of them to authenticate a caller — which a
     * deployment may legitimately configure — is a different question from its {@code User.id}
     * meaning anything here, and the answer to the second is always no. Degrades to the same "not
     * known" the pre-claim path takes, so it needs no separate handling anywhere downstream.
     */
    @Test
    void aUidMintedByASiblingStackIsNotStored() {
        authenticateWith("hc-patient-gateway", "a-patient-stack-user-id");
        when(profileRepository.findByAccountId(LOGIN)).thenReturn(Optional.empty());

        Profile saved = service.upsertOwnProfile(LOGIN, new Profile().firstName("Ama"));

        assertThat(SecurityUtils.getCurrentUserAccountUid()).isEmpty();
        assertThat(saved.getAccountUid()).isNull();
    }

    /**
     * What the profile holds is what is announced — for the profile, never for the caller.
     *
     * <p>Three of the four paths that publish {@code ProfileStatus} are an administrator acting on
     * somebody else's profile, so reading the uid off the calling token instead of the row would
     * attribute the admin's account to the clinician. Asserted here on the one path where the two
     * are the same person, so that a later change to read it from the token fails rather than
     * passes by coincidence.
     */
    @Test
    void theAnnouncementCarriesTheProfilesUidAndNotTheCallers() {
        authenticateWith(SecurityUtils.MINTING_ISSUER, UID);
        when(profileRepository.findByAccountId(LOGIN)).thenReturn(Optional.empty());

        service.upsertOwnProfile(LOGIN, new Profile().firstName("Ama"));

        ArgumentCaptor<String> uid = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(events).publishProfileStatus(
            uid.capture(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any()
        );
        assertThat(uid.getValue()).isEqualTo(UID);
    }

    // ------------------------------------------------------------------ helpers

    /** A token in the shape this gateway minted before 2026-09-07: {@code iss} but no {@code uid}. */
    private void authenticateWithoutUidClaim() {
        authenticate(Map.of("sub", LOGIN, "iss", SecurityUtils.MINTING_ISSUER));
    }

    private void authenticateWith(String issuer, String uid) {
        authenticate(Map.of("sub", LOGIN, "iss", issuer, SecurityUtils.UID_KEY, uid));
    }

    private void authenticate(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600), Map.of("alg", "HS512"), claims);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, jwt, List.of()));
    }
}
