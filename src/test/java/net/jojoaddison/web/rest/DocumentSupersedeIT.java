package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import net.jojoaddison.IntegrationTest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Renewing a credential archives the row it replaces (backlog.md item 20).
 *
 * <p>Renewal used to be a pure insert, so a profile accumulated one document per renewal for ever and
 * both admin surfaces that read those rows counted every licence a clinician had ever held: a
 * professional who renewed last year stayed on {@code /compliance/expiring} permanently, and
 * {@code metrics().expiringLicenses30d} was an ops number that only grew, filled with entries nobody
 * could clear by doing anything.
 *
 * <p><b>The fix is a marker and must never become a delete.</b> A superseded licence is evidence of
 * what a clinician held while they were treating patients, and it is not recoverable after the fact —
 * so every assertion here that an archived row has left a compliance query is paired with one that
 * the row, its bytes and the reviewer's verdict are still there.
 *
 * <p><b>This class and {@link ComplianceFlowIT} test two independent defences and must not be merged.</b>
 * The marker keeps a renewed licence out of the sweep's query; the item 17 guard skips a professional
 * who holds a current licence even when a lapsed row does reach the loop, which is what protects the
 * profiles whose lapsed rows predate this change — there is no migration framework here to backfill
 * them. The discriminator is {@code SweepResult.expiredLicenses}: it is 0 here because the query never
 * returned the row, and 1 in {@code ComplianceFlowIT} because it did and the guard spared the
 * professional anyway.
 */
@AutoConfigureMockMvc
@IntegrationTest
class DocumentSupersedeIT {

    private static final String APPLICANT = "renewing-nurse";
    private static final byte[] PDF_BYTES = "%PDF-1.4 minimal".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private OnboardingEventRepository eventRepository;

    private Profile profile;

    @BeforeEach
    void setUp() {
        cleanup();
        profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(APPLICANT));
    }

    @AfterEach
    void cleanup() {
        personalDocumentRepository.deleteAll();
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void renewingALicenceArchivesTheOldRowAndKeepsEverythingOnIt() throws Exception {
        upload(DocumentType.LICENSE, "licence-2025.pdf", LocalDate.now().minusDays(1), null);
        // The reviewer's verdict on the old licence, recorded before it was replaced. It is the thing
        // most at risk from the alternative design — a fourth VerificationStatus value would have had
        // to overwrite exactly this field to say "superseded", destroying the evidence the row is
        // being kept for. A separate marker pair carries the lifecycle fact beside the verdict.
        personalDocumentRepository.save(byName("licence-2025.pdf").verificationStatus(VerificationStatus.VERIFIED));

        upload(DocumentType.LICENSE, "licence-2026.pdf", LocalDate.now().plusYears(1), null);

        assertThat(personalDocumentRepository.findByProfileId(profile.getId()))
            .as("the superseded row is marked, never removed")
            .hasSize(2);

        PersonalDocument archived = byName("licence-2025.pdf");
        PersonalDocument current = byName("licence-2026.pdf");
        assertThat(archived.getSupersededAt()).isNotNull();
        assertThat(archived.getSupersededByDocumentId()).isEqualTo(current.getId());
        assertThat(archived.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(archived.getExpiryDate()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(archived.getData()).isEqualTo(PDF_BYTES);
        assertThat(archived.getSha256Checksum()).hasSize(64);
        assertThat(current.getSupersededAt()).isNull();
        assertThat(current.getSupersededByDocumentId()).isNull();

        // The credential history stays readable from the clinician's own screen too — hiding the
        // archived row would make a renewal look like a deletion.
        restMockMvc
            .perform(get("/api/onboarding/documents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            // On the wire, not merely in the collection: the marker is what lets a client label an
            // archived row and keep it out of its own checks.
            .andExpect(jsonPath("$[*].supersededAt", hasItem(notNullValue())));
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void onlyTheSameCredentialIsArchivedAndOtherIsMatchedByItsLabel() throws Exception {
        // Two identity documents a clinician legitimately holds at once. Both satisfy the `identity`
        // requirement, and archiving one because the other arrived would hide a credential nobody
        // replaced — so superseding is same-type and nothing looser.
        upload(DocumentType.GHANACARD, "ghana-card.pdf", null, null);
        upload(DocumentType.PASSPORT, "passport.pdf", null, null);
        assertThat(byName("ghana-card.pdf").getSupersededAt()).isNull();

        // OTHER is the free-form catch-all, so it matches on its label as well: a police clearance
        // does not retire an indemnity certificate.
        upload(DocumentType.OTHER, "clearance-2025.pdf", null, "Police clearance");
        upload(DocumentType.OTHER, "indemnity.pdf", null, "Indemnity certificate");
        assertThat(byName("clearance-2025.pdf").getSupersededAt()).isNull();

        upload(DocumentType.OTHER, "clearance-2026.pdf", null, "  police clearance ");
        assertThat(byName("clearance-2025.pdf").getSupersededAt()).as("labels match trimmed and case-insensitively").isNotNull();
        assertThat(byName("indemnity.pdf").getSupersededAt()).as("a different OTHER label is a different document").isNull();
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void theWatchlistTheMetricAndTheSweepAllForgetAnArchivedLicence() throws Exception {
        ProfessionalApplication application = applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(APPLICANT, OnboardingStatus.ACTIVE).login(APPLICANT).profileId(profile.getId())
        );
        upload(DocumentType.LICENSE, "licence-2025.pdf", LocalDate.now().minusDays(1), null);
        personalDocumentRepository.save(byName("licence-2025.pdf").verificationStatus(VerificationStatus.VERIFIED));

        // The lapsed licence is on the watchlist and counted, which is correct while it is the only
        // one this professional holds.
        restMockMvc
            .perform(get("/api/onboarding/compliance/expiring?days=30").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        restMockMvc
            .perform(get("/api/onboarding/compliance/metrics").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiringLicenses30d").value(1));

        upload(DocumentType.LICENSE, "licence-2026.pdf", LocalDate.now().plusYears(1), null);
        personalDocumentRepository.save(byName("licence-2026.pdf").verificationStatus(VerificationStatus.VERIFIED));

        // Renewing is now an action that clears the entry, which is the whole complaint item 20 was
        // opened about: before this, nothing a clinician or an operator could do ever would.
        restMockMvc
            .perform(get("/api/onboarding/compliance/expiring?days=30").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        restMockMvc
            .perform(get("/api/onboarding/compliance/metrics").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiringLicenses30d").value(0));

        // expiredLicenses = 0 is the discriminator between the two defences: the sweep's query never
        // returned the archived row at all. ComplianceFlowIT asserts 1 alongside applicationsSuspended
        // = 0 for the same professional-is-safe outcome reached the other way, through the item 17
        // guard. Neither test can pass for the other's reason.
        restMockMvc
            .perform(post("/api/onboarding/compliance/sweep").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiredLicenses").value(0))
            .andExpect(jsonPath("$.applicationsSuspended").value(0));
        assertThat(applicationRepository.findById(application.getId()).orElseThrow().getStatus()).isEqualTo(OnboardingStatus.ACTIVE);

        // Still two rows: the compliance surfaces stopped counting the old licence, they did not lose it.
        assertThat(personalDocumentRepository.findByProfileId(profile.getId())).hasSize(2);
    }

    @Test
    @WithMockUser(username = APPLICANT, authorities = { "ROLE_USER" })
    void aRejectedDocumentThatHasBeenReplacedCannotBlockApprovalForEver() throws Exception {
        ProfessionalApplication application = applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(APPLICANT, OnboardingStatus.CREDENTIAL_REVIEW)
                .login(APPLICANT)
                .profileId(profile.getId())
        );
        upload(DocumentType.CERTIFICATE, "certificate-blurred.pdf", null, null);
        upload(DocumentType.LICENSE, "licence.pdf", LocalDate.now().plusYears(1), null);
        upload(DocumentType.GHANACARD, "ghana-card.pdf", null, null);
        upload(DocumentType.PASSPHOTO, "photo.pdf", null, null);

        restMockMvc
            .perform(
                put("/api/onboarding/documents/" + byName("certificate-blurred.pdf").getId() + "/reject")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"unreadable scan\"}")
            )
            .andExpect(status().isOk());
        verifyEveryPendingDocument();
        decideApproved(application).andExpect(status().isConflict());

        // The applicant sends a legible scan. The rejected row stays — a reviewer must still be able
        // to see what was refused and why — but it is archived, so it no longer answers the "is every
        // document verified" question. Without that filter `allMatch` would refuse this application
        // for ever, on the strength of a document that had already been replaced.
        upload(DocumentType.CERTIFICATE, "certificate-legible.pdf", null, null);
        verifyEveryPendingDocument();

        PersonalDocument rejected = byName("certificate-blurred.pdf");
        assertThat(rejected.getVerificationStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("unreadable scan");
        assertThat(rejected.getSupersededAt()).isNotNull();

        // Completion is unchanged by the archiving: the replacement satisfies the requirement the
        // archived row used to.
        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.percent").value(100));

        decideApproved(application).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        // The reviewer surface still shows all five, so the refused scan and its reason remain part of
        // the credential history. That is the point of not deleting it.
        restMockMvc
            .perform(get("/api/onboarding/applications/" + application.getId() + "/documents").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5));
    }

    // ------------------------------------------------------------------ helpers

    /** ROLE_ADMIN for the reviewer and compliance calls a test method makes as the applicant. */
    private static RequestPostProcessor admin() {
        return user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * Documents are looked up by filename rather than by the id the upload response carries, because
     * every assertion in this class is about a row's state <em>after</em> a later upload touched it —
     * so it has to be re-read from the collection anyway.
     */
    private PersonalDocument byName(String name) {
        return personalDocumentRepository
            .findByProfileId(profile.getId())
            .stream()
            .filter(document -> name.equals(document.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No document named " + name));
    }

    private void upload(DocumentType type, String filename, LocalDate expiryDate, String otherLabel) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/onboarding/documents")
            .file(new MockMultipartFile("file", filename, MediaType.APPLICATION_PDF_VALUE, PDF_BYTES))
            .param("type", type.name());
        if (expiryDate != null) {
            builder.param("expiryDate", expiryDate.toString());
        }
        if (otherLabel != null) {
            builder.param("otherLabel", otherLabel);
        }
        restMockMvc.perform(builder).andExpect(status().isCreated());
    }

    /** The reviewer clearing the queue — live rows only, since an archived one is not theirs to judge. */
    private void verifyEveryPendingDocument() {
        List<PersonalDocument> pending = personalDocumentRepository
            .findByProfileId(profile.getId())
            .stream()
            .filter(document -> document.getSupersededAt() == null && document.getVerificationStatus() == VerificationStatus.PENDING)
            .map(document -> document.verificationStatus(VerificationStatus.VERIFIED))
            .toList();
        personalDocumentRepository.saveAll(pending);
    }

    private org.springframework.test.web.servlet.ResultActions decideApproved(ProfessionalApplication application) throws Exception {
        return restMockMvc.perform(
            put("/api/onboarding/applications/" + application.getId() + "/decide")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}")
        );
    }
}
