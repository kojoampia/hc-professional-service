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
 * <b>{@code accountId} is the gateway's {@code User.id} and nothing else is</b> (backlog.md item 50).
 *
 * <p>This class replaces {@code AccountUidCutoverUnitTest}, which asserted the opposite arrangement:
 * a login-valued {@code accountId} with the id shadowing it in {@code accountUid}, and an absent
 * claim meaning "carry on with the login". Item 48 built that; item 50 reversed its central decision
 * on the owner's direction, and the direct cutover it sanctions is what the second half of this class
 * pins — <b>a caller whose token carries no usable claim resolves to nobody</b> rather than to their
 * subject.
 *
 * <p>Every case runs against a real {@link SecurityContextHolder}, because the identity is read from
 * the security context and a test that passed the value in as an argument would prove nothing about
 * the wiring that reads it. {@link #LOGIN} and {@link #ACCOUNT_ID} are deliberately unlike each
 * other, so a fallback to the subject cannot pass any assertion here by coincidence.
 */
class AccountIdIsTheUserIdUnitTest {

    private static final String LOGIN = "ama.serwaa";
    private static final String ACCOUNT_ID = "68bd4e2a91c30d5f7a1e4c02";

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
            events,
            mock(OrganizationReferenceValidator.class)
        );

        when(applicationRepository.findByAccountId(anyString())).thenReturn(Optional.empty());
        when(personalDocumentRepository.findByProfileId(anyString())).thenReturn(List.of());
        when(profileRepository.save(any(Profile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * <b>The property the whole item exists for.</b> A clinician's own profile write stores the
     * {@code uid} claim, and stores it in {@code accountId} — the field the estate's correlation key
     * is named after and the field every ownership check looks up.
     *
     * <p>Written as two assertions rather than one, and the negative one is not redundant: an
     * implementation that fell back to the subject would still put <em>something</em> in
     * {@code accountId}, and only a comparison against the login can tell the two apart.
     */
    @Test
    void aProfileWriteStoresTheGatewayUserIdAsTheAccountId() {
        authenticateWith(SecurityUtils.MINTING_ISSUER, ACCOUNT_ID);
        when(profileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        Profile saved = service.upsertOwnProfile(SecurityUtils.getCurrentAccountId().orElseThrow(), new Profile().firstName("Ama"));

        assertThat(saved.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(saved.getAccountId()).isNotEqualTo(LOGIN);
    }

    /**
     * <b>The lookup uses the id too.</b> Storing the id while resolving by the login would be the
     * same defect wearing the fix, and it fails silently: the upsert would create a second profile
     * for a clinician who already has one rather than adopting theirs.
     */
    @Test
    void anExistingProfileIsFoundByTheAccountIdAndNotByTheLogin() {
        authenticateWith(SecurityUtils.MINTING_ISSUER, ACCOUNT_ID);
        Profile stored = new Profile().accountId(ACCOUNT_ID).firstName("Old");
        stored.setId("profile-7");
        when(profileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(stored));
        when(profileRepository.findByAccountId(LOGIN)).thenReturn(Optional.empty());

        Profile saved = service.upsertOwnProfile(SecurityUtils.getCurrentAccountId().orElseThrow(), new Profile().firstName("Ama"));

        assertThat(saved.getId()).isEqualTo("profile-7");
        assertThat(saved.getFirstName()).isEqualTo("Ama");
    }

    /**
     * <b>A token minted before the claim existed resolves to nobody.</b>
     *
     * <p>This is the direct cutover the owner sanctioned on 2026-09-10 and it is the assertion most
     * worth having, because the tempting implementation is the opposite one: fall back to the subject
     * so nothing breaks. That fallback would put a login back into the field this change moved off
     * logins — item 50 § "Do not", <i>the second join key this decision exists to remove,
     * reintroduced under the first one's name</i>. Signing in again is the whole remedy.
     */
    @Test
    void aTokenMintedBeforeTheClaimExistedResolvesToNobodyRatherThanToItsSubject() {
        authenticateWithoutUidClaim();

        assertThat(SecurityUtils.getCurrentAccountId()).isEmpty();
        assertThat(SecurityUtils.getCurrentUserLogin()).contains(LOGIN);
    }

    /**
     * A sibling stack's {@code uid} is discarded, because it names a row in a different user store.
     *
     * <p>hc-admin and hc-patient sign with the same key and {@code TokenOriginValidator} ships
     * disabled, so their tokens authenticate here. Trusting one of them to authenticate a caller —
     * which a deployment may legitimately configure — is a different question from its
     * {@code User.id} meaning anything in this database, and the answer to the second is always no.
     * It degrades to the same "nobody" the pre-claim path takes, so nothing downstream needs a second
     * branch for it.
     */
    @Test
    void aUidMintedByASiblingStackResolvesToNobody() {
        authenticateWith("hc-patient-gateway", "a-patient-stack-user-id");

        assertThat(SecurityUtils.getCurrentAccountId()).isEmpty();
    }

    /** A present-but-blank claim is absent, not an empty string that could match a stored row. */
    @Test
    void aBlankClaimResolvesToNobody() {
        authenticateWith(SecurityUtils.MINTING_ISSUER, "   ");

        assertThat(SecurityUtils.getCurrentAccountId()).isEmpty();
    }

    /**
     * What the profile holds is what is announced — for the profile, never for the caller.
     *
     * <p>Most of the writes that publish {@code ProfileStatus} are an administrator acting on
     * somebody else's profile, so reading the id off the calling token instead of the row would
     * attribute the admin's account to the clinician. Asserted on the one path where the two are the
     * same person, so that a later change to read it from the token fails rather than passing by
     * coincidence.
     *
     * <p>Before item 50 this read {@code Profile.accountUid}; the field is gone and the value it
     * carried is now in {@code accountId}, so the frame is unchanged on the wire.
     */
    @Test
    void theAnnouncementCarriesTheProfilesAccountIdAndNotTheCallers() {
        authenticateWith(SecurityUtils.MINTING_ISSUER, ACCOUNT_ID);
        when(profileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        service.publishProfileStatus(service.upsertOwnProfile(ACCOUNT_ID, new Profile().firstName("Ama")));

        ArgumentCaptor<String> accountId = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(events).publishProfileStatus(
            accountId.capture(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any()
        );
        assertThat(accountId.getValue()).isEqualTo(ACCOUNT_ID);
    }

    /**
     * The application keeps the login beside the id, and they are two different values.
     *
     * <p>{@code startApplication} wrote {@code .accountId(accountId).login(accountId)} until item 50 —
     * one value in two fields, which was correct only because both <em>were</em> the login. The
     * messaging recipient picker renders {@code ProfessionalApplication.login}, so a regression here
     * shows a Mongo id where a name belongs.
     */
    @Test
    void anApplicationCarriesTheIdAsItsKeyAndTheLoginAsItsName() {
        authenticateWith(SecurityUtils.MINTING_ISSUER, ACCOUNT_ID);
        ProfessionalApplicationRepository applications = mock(ProfessionalApplicationRepository.class);
        when(applications.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(applications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        OnboardingService onboarding = new OnboardingService(
            applications,
            mock(OnboardingEventRepository.class),
            profileRepository,
            mock(PersonalDocumentRepository.class),
            events,
            mock(OrganizationReferenceValidator.class)
        );

        var application = onboarding.startApplication(ACCOUNT_ID, LOGIN, "ROLE_NURSE", true, null, null);

        assertThat(application.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(application.getLogin()).isEqualTo(LOGIN);
    }

    // ------------------------------------------------------------------ helpers

    /** A token in the shape this gateway minted before 2026-09-07: {@code iss} but no {@code uid}. */
    private void authenticateWithoutUidClaim() {
        authenticate(Map.of("sub", LOGIN, "iss", SecurityUtils.MINTING_ISSUER));
    }

    private void authenticateWith(String issuer, String accountId) {
        authenticate(Map.of("sub", LOGIN, "iss", issuer, SecurityUtils.UID_KEY, accountId));
    }

    private void authenticate(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600), Map.of("alg", "HS512"), claims);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, jwt, List.of()));
    }
}
