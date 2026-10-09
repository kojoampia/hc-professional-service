package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code POST}/{@code GET /api/personal-document} and {@code GET /{id}/content} — the caller's own
 * credentialing documents (profile.md step 3, T2).
 *
 * <h2>What this class is for, as distinct from its neighbours</h2>
 *
 * <p>{@code ClinicalAuthorityMatrixIT} holds <em>who may call it</em>, including that the new prefix
 * does not reach the plural CRUD surface. {@code OnboardingFlowIT} holds the <em>validation rules</em>
 * — the content-type allowlist, the magic bytes, the size ceiling, the two conditional fields.
 * {@code DocumentSupersedeIT} holds <em>renewal</em>. {@code DocumentUploadLimitIT} is the only class
 * that puts a multi-megabyte body on a real socket.
 *
 * <p>This class holds what is specific to the endpoint being <b>own-scoped</b> and to the body being
 * {@code profile.md}'s specified shape: that the server owns the fields a caller must not claim, that
 * the bytes are never echoed back, that ownership is what gates the one handler which names a
 * subject, and — the property most easily broken by moving a path — that
 * {@code requireEverySubmissionRequirement} still counts documents created this way (named
 * {@code requireMandatoryDocuments} until F-B widened it past step 3).
 *
 * <p>Run as {@code ROLE_USER} throughout: an applicant, which is who step 3 is written by.
 */
@AutoConfigureMockMvc
@IntegrationTest
@WithMockGatewayUser(login = OwnPersonalDocumentResourceIT.APPLICANT, authorities = { "ROLE_USER" })
class OwnPersonalDocumentResourceIT {

    static final String APPLICANT = "own-document-applicant";

    private static final String OWN_DOCUMENT_URL = "/api/personal-document";

    /** The {@code uid} claim {@link WithMockGatewayUser} derives for this class's caller. */
    private static final String CALLER_ACCOUNT = accountIdFor(APPLICANT);

    private static final byte[] PDF_BYTES = "%PDF-1.4 minimal".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc restMockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PersonalDocumentRepository personalDocumentRepository;

    @Autowired
    private ProfessionalApplicationRepository applicationRepository;

    @Autowired
    private OnboardingEventRepository onboardingEventRepository;

    private Profile profile;

    @BeforeEach
    void seedProfile() {
        profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(CALLER_ACCOUNT));
    }

    @AfterEach
    void cleanup() {
        personalDocumentRepository.deleteAll();
        onboardingEventRepository.deleteAll();
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------
    // The write
    // ---------------------------------------------------------------------------------------------

    /**
     * The specified body stores the specified document, with everything else derived here.
     *
     * <p>{@code profile.md}'s model table is {@code name}, {@code profileId}, {@code type},
     * {@code data} and {@code dataContentType} — and it assigns {@code profileId} to the server
     * ("set to {@code Profile.id}"). So the five assertions below are the table: four fields as sent,
     * and the fifth resolved from the caller's token rather than from the request.
     */
    @Test
    void storesTheSpecifiedDocumentAndDerivesProfileIdFromTheCaller() throws Exception {
        upload(DocumentType.CERTIFICATE, "cert.pdf", null, null, null).andExpect(status().isCreated());

        PersonalDocument stored = onlyStoredDocument();
        assertThat(stored.getName()).isEqualTo("cert.pdf");
        assertThat(stored.getType()).isEqualTo(DocumentType.CERTIFICATE);
        assertThat(stored.getData()).isEqualTo(PDF_BYTES);
        assertThat(stored.getDataContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(stored.getProfileId()).isEqualTo(profile.getId());
    }

    /** The credentialing fields the server owns, which no component of the body can carry. */
    @Test
    void derivesTheChecksumSizeStatusAndCreationDate() throws Exception {
        upload(DocumentType.CERTIFICATE, "cert.pdf", null, null, null).andExpect(status().isCreated());

        PersonalDocument stored = onlyStoredDocument();
        assertThat(stored.getSha256Checksum()).hasSize(64);
        assertThat(stored.getSizeBytes()).isEqualTo(PDF_BYTES.length);
        assertThat(stored.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(stored.getCreatedDate()).isEqualTo(LocalDate.now());
    }

    /**
     * ⛔ <b>A body claiming a verdict, an owner or an id gets none of them.</b>
     *
     * <p>This is the case {@code PersonalDocumentUpload} exists for rather than a refusal map:
     * {@code verificationStatus}, {@code profileId} and {@code id} are not components of the request
     * type, so an applicant posting {@code "verificationStatus": "VERIFIED"} does not approve their
     * own credential and one naming somebody else's {@code profileId} does not file a document
     * against their profile. ⚠ <b>An allow-list makes that structural</b>; a deny-list would have to
     * acquire a line every time {@link PersonalDocument} gains a field, which is how
     * {@code ProfileResource}'s {@code PATCH_REFUSED_FIELDS} came to need a heading telling readers
     * not to trust its own count.
     */
    @Test
    void aBodyClaimingServerOwnedFieldsIsNotBelieved() throws Exception {
        restMockMvc
            .perform(
                post(OWN_DOCUMENT_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"spoof.pdf\",\"type\":\"CERTIFICATE\",\"dataContentType\":\"application/pdf\",\"data\":\"" +
                        Base64.getEncoder().encodeToString(PDF_BYTES) +
                        "\",\"id\":\"planted-id\",\"profileId\":\"somebody-else\"," +
                        "\"verificationStatus\":\"VERIFIED\",\"verifiedBy\":\"nobody\",\"supersededAt\":\"2020-01-01T00:00:00Z\"}"
                    )
            )
            .andExpect(status().isCreated());

        PersonalDocument stored = onlyStoredDocument();
        assertThat(stored.getId()).isNotEqualTo("planted-id");
        assertThat(stored.getProfileId()).isEqualTo(profile.getId());
        assertThat(stored.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(stored.getVerifiedBy()).isNull();
        assertThat(stored.getSupersededAt()).isNull();
    }

    /**
     * ⛔ <b>The create response carries no bytes.</b>
     *
     * <p>"Never echo the bytes back in the create response" — the client already has the file it just
     * sent, and a response that repeats it doubles the transfer of an identity document for nothing.
     */
    @Test
    void neverEchoesTheBytesBackOnCreate() throws Exception {
        upload(DocumentType.CERTIFICATE, "cert.pdf", null, null, null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.sha256Checksum").isNotEmpty());

        // The row itself keeps them — nulling the response must never have nulled the document.
        assertThat(onlyStoredDocument().getData()).isEqualTo(PDF_BYTES);
    }

    /** An absent or empty {@code data} is an empty upload, not a document with no content. */
    @Test
    void refusesAnEmptyDocument() throws Exception {
        restMockMvc
            .perform(
                post(OWN_DOCUMENT_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"nothing.pdf\",\"type\":\"CERTIFICATE\",\"dataContentType\":\"application/pdf\"}")
            )
            .andExpect(status().isBadRequest());

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    /**
     * Step 3 depends on step 2, and the refusal says so in words a clinician can act on.
     *
     * <p>{@code PersonalDocument.profileId} is what every reader joins on, so there is nothing to
     * file a document against until the profile exists. ⚠ The message is asserted rather than only
     * the status: a bare 400 on an upload reads as "the file was wrong".
     */
    @Test
    void refusesAnUploadFromAnAccountWithNoProfileYet() throws Exception {
        profileRepository.deleteAll();

        upload(DocumentType.CERTIFICATE, "cert.pdf", null, null, null)
            .andExpect(status().isBadRequest())
            .andExpect(
                result ->
                    assertThat(result.getResponse().getContentAsString()).contains("Create your professional profile before uploading")
            );
    }

    // ---------------------------------------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------------------------------------

    /**
     * The list is the caller's own, archived rows included, and carries no bytes.
     *
     * <p>⛔ The {@code data}-less projection is what lets this read be {@code .authenticated()} at all.
     * A list that inlined the bytes would publish every identity document the account holds to
     * anything that could read the list — which is exactly the shape the plural CRUD surface still
     * has (profile-addendum.md S1) and exactly what this endpoint must not become.
     */
    @Test
    void listsOnlyTheCallersOwnDocumentsWithoutBytes() throws Exception {
        upload(DocumentType.CERTIFICATE, "mine.pdf", null, null, null).andExpect(status().isCreated());
        // Somebody else's row, on a profile this caller does not own.
        Profile stranger = profileRepository.save(new Profile().accountId("uid-a-stranger").firstName("Not").lastName("Mine"));
        personalDocumentRepository.save(
            CompleteOnboardingFixture.document(stranger, DocumentType.PASSPORT, null).name("theirs.pdf").data(PDF_BYTES)
        );

        restMockMvc
            .perform(get(OWN_DOCUMENT_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("mine.pdf"))
            .andExpect(jsonPath("$[0].data").isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // The byte stream — the one handler on this resource that names a subject
    // ---------------------------------------------------------------------------------------------

    /** The owner reads their own scan, which is what a thumbnail on the wizard is. */
    @Test
    void theOwnerStreamsTheirOwnDocument() throws Exception {
        upload(DocumentType.CERTIFICATE, "cert.pdf", null, null, null).andExpect(status().isCreated());

        restMockMvc
            .perform(get(OWN_DOCUMENT_URL + "/{id}/content", onlyStoredDocument().getId()))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PDF_BYTES));
    }

    /**
     * ⛔ <b>Somebody who is not the owner is refused, and that is the whole of this handler's
     * scoping.</b>
     *
     * <p>Unlike the two collection mappings, this one names a subject — a document id — so the path
     * cannot do the scoping and {@code assertOwnerOrReviewer} has to compare the caller against the
     * record. That is the shape the estate's rule warns about, and it is correct here only because
     * there is no alternative: bytes are addressed by id or not at all.
     */
    @Test
    void aCallerWhoIsNotTheOwnerIsRefusedTheBytes() throws Exception {
        upload(DocumentType.CERTIFICATE, "cert.pdf", null, null, null).andExpect(status().isCreated());

        restMockMvc
            .perform(get(OWN_DOCUMENT_URL + "/{id}/content", onlyStoredDocument().getId()).with(someoneElse()))
            .andExpect(status().isForbidden());
    }

    /** The credential reviewer bypasses ownership, because a verdict needs the scan. */
    @Test
    void anAdministratorStreamsAnyDocument() throws Exception {
        upload(DocumentType.CERTIFICATE, "cert.pdf", null, null, null).andExpect(status().isCreated());

        restMockMvc.perform(get(OWN_DOCUMENT_URL + "/{id}/content", onlyStoredDocument().getId()).with(admin())).andExpect(status().isOk());
    }

    /** An unknown id is a 404 and not a 403, so an id cannot be probed for existence. */
    @Test
    void anUnknownDocumentIdIsNotFoundRatherThanForbidden() throws Exception {
        restMockMvc
            .perform(get(OWN_DOCUMENT_URL + "/{id}/content", "no-such-document").with(someoneElse()))
            .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------------
    // ⛔ The gate that moving a path is most likely to break
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>The submission gate still counts documents uploaded through the new endpoint.</b>
     *
     * <p>This is the property T2 most easily breaks and the one no other test would notice.
     * {@code OnboardingService.requireEverySubmissionRequirement} joins on {@code PersonalDocument.profileId}
     * and demands a {@code CERTIFICATE}, a {@code LICENSE} <em>with an expiry date</em>, one of the
     * four identity types and a {@code PASSPHOTO} — all <b>live</b>. Nothing about that gate names an
     * endpoint, so a path change cannot fail it by construction; what would fail it is deriving
     * {@code profileId} differently, or storing the expiry date differently, after which <b>the
     * wizard's final button 400s for ever</b> with four documents visibly uploaded.
     *
     * <p>⚠ Asserted in both directions in one walk — refused before the four, accepted after — so a
     * green result cannot come from a submit that was never capable of refusing. The fixtures
     * deliberately do <em>not</em> seed the documents here: the whole point is that these four
     * arrive over HTTP through the endpoint under test.
     */
    @Test
    void theFourMandatoryDocumentsUploadedHereSatisfySubmitForReview() throws Exception {
        restMockMvc
            .perform(
                post("/api/professional-application")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true}")
            )
            .andExpect(status().isCreated());
        restMockMvc.perform(put("/api/professional-application/me/complete-profile")).andExpect(status().isOk());

        // Red first, in the same walk: without the documents the submit is refused.
        restMockMvc
            .perform(
                put("/api/professional-application/me/submit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true}")
            )
            .andExpect(status().isBadRequest());

        upload(DocumentType.CERTIFICATE, "certificate.pdf", null, null, null).andExpect(status().isCreated());
        upload(DocumentType.LICENSE, "licence.pdf", LocalDate.now().plusYears(1), null, null).andExpect(status().isCreated());
        upload(DocumentType.GHANACARD, "ghana-card.pdf", null, null, null).andExpect(status().isCreated());
        upload(DocumentType.PASSPHOTO, "photo.pdf", null, null, null).andExpect(status().isCreated());

        restMockMvc
            .perform(
                put("/api/professional-application/me/submit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CREDENTIAL_REVIEW"));
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * One {@code POST} carrying {@code profile.md}'s specified body: {@code data} is the uploaded
     * file, base64 on the wire, which is what a {@code byte[]} field means over HTTP.
     */
    private ResultActions upload(DocumentType type, String name, LocalDate expiryDate, String otherLabel, String supersedesDocumentId)
        throws Exception {
        StringBuilder body = new StringBuilder("{\"name\":\"")
            .append(name)
            .append("\",\"type\":\"")
            .append(type.name())
            .append("\",\"dataContentType\":\"")
            .append(MediaType.APPLICATION_PDF_VALUE)
            .append("\",\"data\":\"")
            .append(Base64.getEncoder().encodeToString(PDF_BYTES))
            .append("\"");
        if (expiryDate != null) {
            body.append(",\"expiryDate\":\"").append(expiryDate).append("\"");
        }
        if (otherLabel != null) {
            body.append(",\"otherLabel\":\"").append(otherLabel).append("\"");
        }
        if (supersedesDocumentId != null) {
            body.append(",\"supersedesDocumentId\":\"").append(supersedesDocumentId).append("\"");
        }
        body.append("}");
        return restMockMvc.perform(post(OWN_DOCUMENT_URL).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private PersonalDocument onlyStoredDocument() {
        List<PersonalDocument> stored = personalDocumentRepository.findByProfileId(profile.getId());
        assertThat(stored).hasSize(1);
        return stored.get(0);
    }

    /**
     * A caller who is authenticated and is nobody's profile.
     *
     * <p>No {@code uid} claim, so {@code SecurityUtils.getCurrentAccountId()} resolves to nothing and
     * the ownership check finds no profile — which is the right model of a token this gateway did not
     * mint, since item 50 removed the fallback to the login.
     */
    private static RequestPostProcessor someoneElse() {
        return user("a-stranger").authorities(new SimpleGrantedAuthority("ROLE_NURSE"));
    }

    private static RequestPostProcessor admin() {
        return user("reviewer").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
