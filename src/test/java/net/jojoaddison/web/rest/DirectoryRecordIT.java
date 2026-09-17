package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The composed directory read hc-admin needs (backlog.md item 51):
 * {@code GET /api/professionals/{accountId}/directory-record}.
 *
 * <p>Each guarded decision is exercised on its own rather than through one end-to-end path, because
 * every one of them fails <em>plausibly</em>: a record assembled from the wrong licence, or from a
 * rejected applicant's requested role, reads exactly like a correct one on the far side. There is no
 * screen in hc-admin on which a mis-mapped clinician looks wrong — their item 35 says so — so the
 * only place the difference is visible is here.
 *
 * @see net.jojoaddison.web.rest.ProfessionalDirectoryResource for the authority decision.
 */
@AutoConfigureMockMvc
@IntegrationTest
class DirectoryRecordIT {

    private static final String URL = "/api/professionals/{accountId}/directory-record";

    private static final String PRO = "directory-pro";

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @BeforeEach
    @AfterEach
    void cleanup() {
        personalDocumentRepository.deleteAll();
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
    }

    /**
     * The positive control: everything present, everything projected.
     *
     * <p><b>The key-count assertion is the point of this test, not decoration.</b> The record is
     * assembled from {@code Profile}, which also holds a card number, a birth date, a mobile number
     * and an address — so the risk this endpoint carries is not a missing field but an extra one,
     * added later by someone widening the projection because it was convenient. Six keys, named.
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void aFullyPopulatedClinicianReturnsNameRoleAndLicenceStatus() throws Exception {
        Profile profile = aProfile();
        anApplication(profile, OnboardingStatus.ACTIVE, "ROLE_NURSE");
        aLicence(profile, VerificationStatus.VERIFIED, LocalDate.now().plusYears(1));

        restMockMvc
            .perform(get(URL, accountIdFor(PRO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(accountIdFor(PRO)))
            .andExpect(jsonPath("$.firstName").value("Appli"))
            .andExpect(jsonPath("$.lastName").value("Cant"))
            .andExpect(jsonPath("$.role").value("ROLE_NURSE"))
            .andExpect(jsonPath("$.onboardingStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.licenceVerified").value(true))
            .andExpect(jsonPath("$.*", hasSize(6)));
    }

    /**
     * An account with neither row is 404 — <b>not a record with every field missing</b>.
     *
     * <p>An empty record would invite the consumer to write a directory row for a clinician this
     * service has never heard of, which on the far side reads as a rendering fault rather than as a
     * bad identifier. The body is asserted empty as well as the status, because a 404 carrying a
     * half-built record would be the same defect wearing the right number.
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void anAccountThisServiceHasNeverHeardOfIsNotFoundRatherThanAnEmptyRecord() throws Exception {
        String body = restMockMvc
            .perform(get(URL, "uid-nobody-at-all"))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain("accountId").doesNotContain("firstName");
    }

    /**
     * A clinician created by admin invitation has a profile before they file an application. The
     * record still returns; the two application-sourced fields are <b>absent</b>, not null.
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void aClinicianWithNoApplicationKeepsTheirNameAndOmitsTheRole() throws Exception {
        Profile profile = aProfile();
        aLicence(profile, VerificationStatus.VERIFIED, LocalDate.now().plusYears(1));

        restMockMvc
            .perform(get(URL, accountIdFor(PRO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Appli"))
            .andExpect(jsonPath("$.licenceVerified").value(true))
            .andExpect(jsonPath("$.role").doesNotExist())
            .andExpect(jsonPath("$.onboardingStatus").doesNotExist());
    }

    /**
     * And the converse, which is why the record is not anchored on {@code Profile} alone: an
     * applicant files an application before completing a profile, and hc-admin is told they exist by
     * an event at exactly that moment. Answering 404 here would mean "no such clinician" about
     * somebody this service is actively onboarding.
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void anApplicantWithNoProfileYetKeepsTheirRoleAndOmitsTheName() throws Exception {
        anApplication(null, OnboardingStatus.APPLICATION_STARTED, "ROLE_PARAMEDIC");

        restMockMvc
            .perform(get(URL, accountIdFor(PRO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ROLE_PARAMEDIC"))
            .andExpect(jsonPath("$.onboardingStatus").value("APPLICATION_STARTED"))
            .andExpect(jsonPath("$.firstName").doesNotExist())
            .andExpect(jsonPath("$.lastName").doesNotExist())
            .andExpect(jsonPath("$.licenceVerified").doesNotExist());
    }

    /**
     * No licence on file at all: the fact is <b>absent</b>, and the call does not fail.
     *
     * <p>Paired deliberately with the test below, because {@code false} and absent are the two
     * answers this field can give and they send different people to work — a reviewer or a renewal
     * for {@code false}, the applicant themselves for absent. A projection that reported {@code
     * false} for both would pass every other test in this class.
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void aClinicianWithNoLicenceOmitsTheFactRatherThanReportingFalse() throws Exception {
        Profile profile = aProfile();
        anApplication(profile, OnboardingStatus.PROFILE_COMPLETED, "ROLE_CARER");
        personalDocumentRepository.save(CompleteOnboardingFixture.document(profile, DocumentType.GHANACARD, null));

        restMockMvc
            .perform(get(URL, accountIdFor(PRO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ROLE_CARER"))
            .andExpect(jsonPath("$.licenceVerified").doesNotExist());
    }

    /** A licence on file and not yet vetted is {@code false} — present, and negative. */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void aLicenceAwaitingAReviewerIsFalseRatherThanAbsent() throws Exception {
        Profile profile = aProfile();
        anApplication(profile, OnboardingStatus.CREDENTIAL_REVIEW, "ROLE_DOCTOR");
        aLicence(profile, VerificationStatus.PENDING, LocalDate.now().plusYears(1));

        restMockMvc.perform(get(URL, accountIdFor(PRO))).andExpect(status().isOk()).andExpect(jsonPath("$.licenceVerified").value(false));
    }

    /**
     * A superseded licence is credential history and decides nothing — in <b>both</b> directions.
     *
     * <p>Two subjects, each holding a live row and an archived one that disagrees with it, so
     * neither half can pass by accident: an implementation that ignored {@code supersededAt}
     * entirely would answer {@code true} for the first and could answer either for the second.
     * "Newest by {@code expiryDate}" is refuted by the first subject, whose archived row expires
     * later; "the verified one" by the same subject, whose archived row is the verified one.
     */
    @Test
    @WithMockGatewayUser(login = "admin", authorities = { "ROLE_ADMIN" })
    void aSupersededLicenceIsIgnoredAndTheCurrentOneDecides() throws Exception {
        Profile stale = aProfile();
        anApplication(stale, OnboardingStatus.ACTIVE, "ROLE_NURSE");
        personalDocumentRepository.save(
            CompleteOnboardingFixture.document(
                stale,
                DocumentType.LICENSE,
                LocalDate.now().plusYears(9),
                VerificationStatus.VERIFIED
            ).supersededAt(Instant.now())
        );
        aLicence(stale, VerificationStatus.PENDING, LocalDate.now().plusYears(1));

        restMockMvc.perform(get(URL, accountIdFor(PRO))).andExpect(status().isOk()).andExpect(jsonPath("$.licenceVerified").value(false));

        Profile renewed = profileRepository.save(
            CompleteOnboardingFixture.completeProfile(accountIdFor("renewed-pro")).firstName("Renewed")
        );
        personalDocumentRepository.save(
            CompleteOnboardingFixture.document(
                renewed,
                DocumentType.LICENSE,
                LocalDate.now().minusDays(1),
                VerificationStatus.REJECTED
            ).supersededAt(Instant.now())
        );
        aLicence(renewed, VerificationStatus.VERIFIED, LocalDate.now().plusYears(1));

        restMockMvc
            .perform(get(URL, accountIdFor("renewed-pro")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.licenceVerified").value(true));
    }

    /**
     * The authority rule: a clinician of this stack is refused, however senior their discipline.
     *
     * <p>No product surface here calls this endpoint — {@code web/} and {@code mobile/} have their
     * own screens over the same rows — so admitting the clinical disciplines would open one
     * colleague's identity and credential state to every other for the benefit of no caller.
     */
    @Test
    @WithMockGatewayUser(login = PRO, authorities = { "ROLE_DOCTOR" })
    void aClinicianIsRefused() throws Exception {
        Profile profile = aProfile();
        anApplication(profile, OnboardingStatus.ACTIVE, "ROLE_NURSE");

        restMockMvc.perform(get(URL, accountIdFor(PRO))).andExpect(status().isForbidden());
    }

    /** And a caller with no authorities at all — a role-less applicant, or a sibling stack's user. */
    @Test
    @WithMockGatewayUser(login = "applicant")
    void aRoleLessCallerIsRefused() throws Exception {
        restMockMvc.perform(get(URL, accountIdFor(PRO))).andExpect(status().isForbidden());
    }

    /** No token at all — the state hc-admin's Kafka consumer is in, and it is a 401, never a 403. */
    @Test
    @WithUnauthenticatedMockUser
    void anUnauthenticatedCallerIsRefused() throws Exception {
        restMockMvc.perform(get(URL, accountIdFor(PRO))).andExpect(status().isUnauthorized());
    }

    /**
     * The refusal precedes the lookup, so 403 and 404 cannot be used as an existence oracle.
     *
     * <p>A refused caller asking about an account that does not exist gets the same 403 as one
     * asking about an account that does — which is what putting the rule on the class rather than
     * inside the handler buys.
     */
    @Test
    @WithMockGatewayUser(login = PRO, authorities = { "ROLE_DOCTOR" })
    void aRefusedCallerCannotTellAnExistingAccountFromAnAbsentOne() throws Exception {
        anApplication(aProfile(), OnboardingStatus.ACTIVE, "ROLE_NURSE");

        restMockMvc.perform(get(URL, accountIdFor(PRO))).andExpect(status().isForbidden());
        restMockMvc.perform(get(URL, "uid-nobody-at-all")).andExpect(status().isForbidden());
    }

    private Profile aProfile() {
        return profileRepository.save(CompleteOnboardingFixture.completeProfile(accountIdFor(PRO)));
    }

    private void anApplication(Profile profile, OnboardingStatus status, String requestedRole) {
        applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(accountIdFor(PRO), status)
                .login(PRO)
                .profileId(profile == null ? null : profile.getId())
                .requestedRole(requestedRole)
        );
    }

    private PersonalDocument aLicence(Profile profile, VerificationStatus verificationStatus, LocalDate expiry) {
        return personalDocumentRepository.save(
            CompleteOnboardingFixture.document(profile, DocumentType.LICENSE, expiry, verificationStatus)
        );
    }
}
