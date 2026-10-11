package net.jojoaddison.web.rest;

import static net.jojoaddison.security.WithMockGatewayUser.Factory.accountIdFor;
import static net.jojoaddison.security.WithMockGatewayUser.Factory.gatewayUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.ProfileStatus;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.OnboardingEventRepository;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.WithMockGatewayUser;
import net.jojoaddison.service.ProfileCompleteness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code POST /api/personal-document/avatar} — the clinician's profile photograph (backlog.md row 232).
 *
 * <h2>What this class is for, as distinct from its neighbours</h2>
 *
 * <p>{@link OwnPersonalDocumentResourceIT} holds the <em>credential</em> upload on the same base. This
 * class holds everything about the avatar that differs from it, and the differences are the whole
 * reason the avatar has its own handler: it may arrive <b>before the profile exists</b>, its ceiling
 * is 2 MB rather than 5, it accepts two content types rather than three, <b>its metadata is
 * stripped</b>, it replaces rather than archives, and — the half most of these tests are about — it
 * <b>counts towards nothing</b>.
 *
 * <p>⛔ <b>"Counts towards nothing" is driven through the real predicates, never through copies of
 * them.</b> Three separate things could make an avatar count, each with a different blast radius, and
 * each is exercised by the surface a clinician or a reviewer actually calls: the progress meter
 * ({@code GET /api/onboarding/progress}), the submit gate
 * ({@code PUT /api/professional-application/me/submit}) and <b>the approval gate</b>
 * ({@code PUT /api/professional-application/{id}/decide}) — the last of which was the one that would
 * genuinely have broken, since {@code OnboardingService.allLiveDocumentsVerified} is an
 * {@code allMatch(VERIFIED)} over a profile's live documents and nothing in the estate can ever
 * verify an avatar.
 *
 * <p>Run as {@code ROLE_USER} throughout, because that is all an applicant holds and step 1 is
 * performed by an applicant. A test needing a reviewer says so with a post-processor.
 */
@AutoConfigureMockMvc
@IntegrationTest
@WithMockGatewayUser(login = OwnPersonalDocumentAvatarIT.APPLICANT, authorities = { "ROLE_USER" })
class OwnPersonalDocumentAvatarIT {

    static final String APPLICANT = "avatar-applicant";

    private static final String OWN_DOCUMENT_URL = "/api/personal-document";
    private static final String AVATAR_URL = OWN_DOCUMENT_URL + "/avatar";

    private static final String CALLER_ACCOUNT = accountIdFor(APPLICANT);

    /**
     * A marker that would be a clinician's home address on a real photograph.
     *
     * <p>Asserted <b>absent from the stored bytes</b> and <b>present in what was sent</b>, in the same
     * test — otherwise a strip that silently did nothing and a payload that never carried the marker
     * are indistinguishable, and the test would be green for the wrong reason.
     */
    private static final String EXIF_MARKER = "GPSLatitude=5.6037";

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

    /**
     * ⚠ <b>Nothing is seeded.</b> Unlike its sibling class this one creates no profile up front,
     * because "the caller has no profile yet" is the ordinary state of step 1 and is the state several
     * of these tests are about. A test that needs a complete profile builds one itself.
     */
    @AfterEach
    void cleanup() {
        personalDocumentRepository.deleteAll();
        onboardingEventRepository.deleteAll();
        applicationRepository.deleteAll();
        profileRepository.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------
    // Step 1 precedes step 2, so the profile may not exist
    // ---------------------------------------------------------------------------------------------

    /**
     * ⭐ <b>An avatar from an account with no profile is accepted, and creates the row.</b>
     *
     * <p>This is the property the whole handler exists for. The credential upload beside it answers
     * 400 — <i>"create your professional profile before uploading documents"</i> — which is right for
     * step 3 and would make step 1 unsatisfiable: the dialog opens because {@code imageUrl} is empty,
     * the upload is refused because step 2 has not run, and the dialog reopens for ever.
     *
     * <p>⚠ <b>And the row it creates must stay incomplete.</b> {@code ProfileCompleteness} wants 39
     * values, so a document holding only an {@code accountId} cannot be {@code PROFILE_COMPLETED} —
     * asserted here through the real predicate rather than by reading {@code status}, because the
     * claim is about what the predicate says and not only about what this write happened to store.
     */
    @Test
    void anAvatarFromAnAccountWithNoProfileCreatesTheProfileRow() throws Exception {
        assertThat(profileRepository.findAll()).isEmpty();

        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());

        List<Profile> profiles = profileRepository.findAll();
        assertThat(profiles).hasSize(1);
        Profile created = profiles.get(0);
        assertThat(created.getAccountId()).isEqualTo(CALLER_ACCOUNT);
        assertThat(created.getFirstName()).isNull();
        assertThat(created.getStatus()).isNull();
        assertThat(ProfileCompleteness.isComplete(created)).isFalse();
    }

    /**
     * ⛔ <b>A second avatar adopts the row it finds, and does not make a second profile.</b>
     *
     * <p>Nothing in the schema forbids two profiles under one {@code accountId}, so a create-always
     * path would let one account accumulate them — the reasoning backlog.md item 66 rejected a
     * token-forced {@code POST /api/profiles} on. Asserted over {@code findAll}, not over
     * {@code findByAccountId}, which would answer with one of two rows and look correct.
     */
    @Test
    void aSecondAvatarAdoptsTheProfileRatherThanCreatingAnother() throws Exception {
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());
        postAvatar(png(), MediaType.IMAGE_PNG_VALUE).andExpect(status().isCreated());

        assertThat(profileRepository.findAll()).hasSize(1);
    }

    /** A profile written by step 2 afterwards fills in the row the avatar created, rather than a new one. */
    @Test
    void aLaterProfileWriteFillsInTheRowTheAvatarCreated() throws Exception {
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());
        String avatarCreatedProfileId = profileRepository.findAll().get(0).getId();

        restMockMvc
            .perform(put("/api/profile").contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Appli\",\"lastName\":\"Cant\"}"))
            .andExpect(status().isOk());

        List<Profile> profiles = profileRepository.findAll();
        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).getId()).isEqualTo(avatarCreatedProfileId);
        assertThat(profiles.get(0).getFirstName()).isEqualTo("Appli");
        // And the avatar is still attached to it — the adoption must not orphan the document.
        assertThat(personalDocumentRepository.findByProfileId(avatarCreatedProfileId)).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------
    // The refusals, one assertion each
    // ---------------------------------------------------------------------------------------------

    /**
     * Over 2 MB decoded, with the limit named.
     *
     * <p>⚠ The message is asserted and not only the status, for the reason the ceiling chain's comment
     * gives: this is the only rung an avatar reaches that can say anything at all, since nginx's is a
     * bare 413 with no body. A 400 that does not name the number is indistinguishable from "your file
     * was wrong somehow".
     */
    @Test
    void refusesAnAvatarOverTwoMegabytes() throws Exception {
        byte[] oversize = new byte[2_000_001];
        // A real JPEG header, so this fails on size and on nothing else.
        System.arraycopy(jpeg(), 0, oversize, 0, 16);

        postAvatar(oversize, MediaType.IMAGE_JPEG_VALUE)
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("2 MB"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    /** A PDF is a credential scan and is not an avatar, however well-formed it is. */
    @Test
    void refusesAPdfAvatar() throws Exception {
        postAvatar("%PDF-1.4 minimal".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_PDF_VALUE)
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Only PNG and JPEG"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    /**
     * ⛔ <b>The declared content type is held to the content.</b>
     *
     * <p>PNG bytes under {@code image/jpeg}: the allow-list admits the label, so without the magic-byte
     * check this would pass validation and then be re-encoded as the wrong format. Both halves matter
     * here, which is why this case is on the avatar path too rather than left to the sibling class.
     */
    @Test
    void refusesPngBytesDeclaredAsJpeg() throws Exception {
        postAvatar(png(), MediaType.IMAGE_JPEG_VALUE)
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("does not match its declared type"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    /**
     * ⭐ <b>Four correct bytes in front of rubbish is refused, and only the re-encode can refuse it.</b>
     *
     * <p>{@code magicBytesMatch} passes this input — it reads three bytes — so this test is the proof
     * that the metadata strip is also a validation, and the reason deleting the strip would quietly
     * delete a check.
     */
    @Test
    void refusesBytesThatBeginLikeAJpegButAreNotAnImage() throws Exception {
        byte[] notAnImage = new byte[64];
        notAnImage[0] = (byte) 0xFF;
        notAnImage[1] = (byte) 0xD8;
        notAnImage[2] = (byte) 0xFF;

        postAvatar(notAnImage, MediaType.IMAGE_JPEG_VALUE)
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("could not be read as an image"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // ⛔ Decompression bombs — a tiny body that claims an enormous raster
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔⛔ <b>691 bytes that would have allocated 1.6 GB, and answered 500 doing it.</b>
     *
     * <p>A JPEG's {@code SOF0} segment carries the dimensions, and a decoder believes it: patching one
     * to 20000×20000 makes a <b>691-byte body</b> ask for a 400-megapixel raster. Found in review and
     * measured on the production heap ({@code -Xmx384m}, {@code deploy/prod-server/compose.yml}):
     * {@code ImageIO.read} raised a bare {@code java.lang.OutOfMemoryError}, which is an
     * {@link Error} and therefore walks straight past a {@code catch (IOException)} — so the answer
     * was <b>500</b>, not the 400 the handler's javadoc promised, and four concurrent requests OOMed
     * three of four JVMs.
     *
     * <p>⭐ <b>The fix is a header read, not a bigger {@code catch}</b> — the dimensions are available
     * from {@code ImageReader.getWidth(0)} with no raster allocated at all, so this is refused
     * <em>before</em> anything is decoded. ⛔ Catching {@code OutOfMemoryError} was the obvious repair
     * and is the wrong one: it would still allocate first, and it would leave the far worse case —
     * an image that decodes <em>successfully</em> into a third of the heap — completely untouched.
     *
     * <p>⚠ The assertion is on the <b>megapixel message</b> and not merely on 400, because the
     * pre-fix behaviour for the PNG case below was already a 400 — for the wrong reason, after the
     * allocation. Only the named limit distinguishes "refused by the bound" from "crashed and
     * recovered".
     */
    @Test
    void refusesAJpegWhoseHeaderClaimsAnEnormousRaster() throws Exception {
        byte[] bomb = jpegWithPatchedDimensions(20_000, 20_000);
        assertThat(bomb.length).isLessThan(2_000);

        postAvatar(bomb, MediaType.IMAGE_JPEG_VALUE)
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("megapixel"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    /**
     * ⛔ <b>The same bomb as a PNG, where the pre-fix answer was a 400 that proved nothing.</b>
     *
     * <p>A patched {@code IHDR} claiming 20000×20000 in a few hundred bytes. Before the bound this
     * reached {@code ImageIO.read}, attempted a ~400 MB allocation and then failed on the truncated
     * pixel data — a 400, but <em>after</em> the amplification, and indistinguishable in the response
     * from a corrupt file. ⚠ So this test asserts the limit is <b>named</b>: that is the only
     * observable difference between the bound doing its job and the decoder falling over.
     */
    @Test
    void refusesAPngWhoseHeaderClaimsAnEnormousRaster() throws Exception {
        byte[] bomb = pngWithPatchedDimensions(20_000, 20_000);
        assertThat(bomb.length).isLessThan(2_000);

        postAvatar(bomb, MediaType.IMAGE_PNG_VALUE)
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("megapixel"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    /**
     * ⛔ <b>A frame whose pixel count overflows {@code int} — and it has to be a RECTANGLE, which is
     * the part that was nearly got wrong.</b>
     *
     * <p>60000×40000 is 2,400,000,000 pixels. In {@code int} arithmetic that overflows to
     * <b>−1,894,967,296</b> — negative, so it passes {@code pixels > MAX_PIXELS} cheerfully and hands
     * the decoder a 2.4-gigapixel raster. The bound is computed in {@code long} for exactly this
     * input: with the multiplication narrowed to {@code int} this is the only test in the class that
     * fails, which is how it was verified.
     *
     * <p>⚠ <b>The obvious input — 65535×65535, the largest a JPEG can declare — does NOT exercise
     * this and would have passed for the wrong reason.</b> Measured: the platform JPEG reader refuses
     * it on the header read with <i>"Maximum supported image dimension is 65500 pixels"</i>, so it
     * never reaches the bound at all and is refused as an unreadable image. A test written with it
     * would have been green with the overflow bug present. <b>So the case is a wide rectangle, not a
     * big square</b>, and both dimensions here are under the reader's own cap on purpose.
     */
    @Test
    void refusesAFrameWhosePixelCountOverflowsAnInt() throws Exception {
        byte[] bomb = jpegWithPatchedDimensions(60_000, 40_000);

        postAvatar(bomb, MediaType.IMAGE_JPEG_VALUE)
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("megapixel"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    /**
     * ⭐ <b>The positive control the bound exists to keep working: a real phone photograph.</b>
     *
     * <p>4032×3024 is what a mainstream phone camera emits and what the marketing calls "12 Mpx" —
     * ⚠ <b>it is 12,192,768 pixels, which is MORE than 12,000,000</b>, so a literal 12-megapixel
     * bound would have rejected the exact image it was chosen to admit. That trap is why
     * {@code MAX_PIXELS} is 16 Mpx and why this test states the real frame size rather than a round
     * number.
     *
     * <p>It also asserts the <b>stored</b> consequences, which are the other half of the review
     * finding: the re-encode used to be able to <em>grow</em> the file past the 2 MB rung, so
     * {@code sizeBytes} escaped the ceiling entirely. After downscaling to a rendering-sized edge the
     * stored length is a <b>checked</b> property rather than an argument.
     */
    @Test
    void acceptsATwelveMegapixelPhotographAndStoresItDownscaled() throws Exception {
        byte[] photo = photoShapedJpeg(4032, 3024);

        postAvatar(photo, MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());

        PersonalDocument stored = onlyAvatar();
        BufferedImage storedImage = ImageIO.read(new ByteArrayInputStream(stored.getData()));
        assertThat(storedImage).isNotNull();
        assertThat(Math.max(storedImage.getWidth(), storedImage.getHeight())).isEqualTo(1024);
        // The aspect ratio survives the downscale — 4032x3024 is 4:3, so 1024x768.
        assertThat(storedImage.getWidth()).isEqualTo(1024);
        assertThat(storedImage.getHeight()).isEqualTo(768);
        // ⛔ The stored bound, asserted rather than argued.
        assertThat(stored.getSizeBytes()).isEqualTo(stored.getData().length).isLessThanOrEqualTo(2_000_000L);
    }

    /** A small avatar is not upscaled — the bound is a ceiling on the edge, not a target. */
    @Test
    void doesNotUpscaleAnAvatarSmallerThanTheRenderingEdge() throws Exception {
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());

        BufferedImage storedImage = ImageIO.read(new ByteArrayInputStream(onlyAvatar().getData()));
        assertThat(storedImage.getWidth()).isEqualTo(8);
        assertThat(storedImage.getHeight()).isEqualTo(8);
    }

    /** An absent or empty {@code data} is an empty upload, as on the credential path. */
    @Test
    void refusesAnEmptyAvatar() throws Exception {
        restMockMvc
            .perform(post(AVATAR_URL).contentType(MediaType.APPLICATION_JSON).content("{\"dataContentType\":\"image/jpeg\"}"))
            .andExpect(status().isBadRequest());

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // ⛔ EXIF — the one refusal that is about what is stored rather than what is refused
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>EXIF does not survive the upload, asserted on the stored row.</b>
     *
     * <p>A phone photograph carries GPS coordinates and this estate's payload rule is <i>identifiers
     * and metadata only</i>; the byte stream serves an avatar to the owner <em>or a reviewer</em>, so
     * unstripped coordinates would be a clinician's home address handed to an administrator. Asserted
     * against the database rather than against a response, because the response carries no bytes at
     * all and could never show this.
     *
     * <p>⚠ <b>Both directions in one test.</b> The marker is asserted present in what was sent and
     * absent from what was stored — a strip that did nothing and a payload that never carried a marker
     * would otherwise look identical.
     */
    @Test
    void stripsExifFromTheStoredBytes() throws Exception {
        byte[] sent = jpegWithExif();
        assertThat(new String(sent, StandardCharsets.ISO_8859_1)).contains(EXIF_MARKER);

        postAvatar(sent, MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());

        PersonalDocument stored = onlyAvatar();
        assertThat(new String(stored.getData(), StandardCharsets.ISO_8859_1)).doesNotContain(EXIF_MARKER);
        // Still a readable JPEG afterwards, so the scrub did not merely corrupt the file.
        assertThat(ImageIO.read(new ByteArrayInputStream(stored.getData()))).isNotNull();
        // The checksum and size describe the bytes that were KEPT, not the ones that arrived.
        assertThat(stored.getSizeBytes()).isEqualTo(stored.getData().length).isNotEqualTo((long) sent.length);
        assertThat(stored.getSha256Checksum()).hasSize(64);
    }

    /** A PNG survives the same round trip and is still a PNG. */
    @Test
    void reEncodesAPngAsAPng() throws Exception {
        postAvatar(png(), MediaType.IMAGE_PNG_VALUE).andExpect(status().isCreated());

        PersonalDocument stored = onlyAvatar();
        assertThat(stored.getDataContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(stored.getName()).isEqualTo("avatar.png");
        assertThat(ImageIO.read(new ByteArrayInputStream(stored.getData()))).isNotNull();
    }

    // ---------------------------------------------------------------------------------------------
    // Replacement — one live avatar, by deletion
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>A new avatar deletes the previous row rather than archiving it.</b>
     *
     * <p>Asserted three ways, because "there is one avatar" is the claim and a count alone would pass
     * on a row that had merely been marked: exactly one {@code AVATAR} document exists, its id is a
     * new one, and <b>the old id answers 404</b> — so the row is gone from the collection and not
     * sitting in it with {@code supersededAt} set. The argument for deletion rather than supersede is
     * on the handler: nobody reviews an avatar, so a retained series of a person's face is storage and
     * privacy cost with no reader.
     */
    @Test
    void replacingTheAvatarDeletesThePreviousRow() throws Exception {
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());
        String firstId = onlyAvatar().getId();

        postAvatar(png(), MediaType.IMAGE_PNG_VALUE).andExpect(status().isCreated());

        assertThat(avatars()).hasSize(1);
        assertThat(onlyAvatar().getId()).isNotEqualTo(firstId);
        assertThat(onlyAvatar().getSupersededAt()).isNull();
        assertThat(personalDocumentRepository.findById(firstId)).isEmpty();
        restMockMvc.perform(get(OWN_DOCUMENT_URL + "/{id}/content", firstId)).andExpect(status().isNotFound());
    }

    /** ⚠ Replacement is scoped to avatars: it must not sweep away the credentials beside them. */
    @Test
    void replacingTheAvatarLeavesCredentialsAlone() throws Exception {
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());
        Profile profile = profileRepository.findByAccountId(CALLER_ACCOUNT).orElseThrow();
        personalDocumentRepository.save(CompleteOnboardingFixture.document(profile, DocumentType.CERTIFICATE, null));

        postAvatar(png(), MediaType.IMAGE_PNG_VALUE).andExpect(status().isCreated());

        assertThat(personalDocumentRepository.findByProfileId(profile.getId())).hasSize(2);
        assertThat(avatars()).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------
    // ⛔ The other two create paths refuse the type and name this one
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>The generic credential {@code POST} refuses {@code AVATAR} and names the avatar path.</b>
     *
     * <p>The precedent is {@code POST /api/profiles}, which refuses and names the owner path rather
     * than storing something subtly wrong and answering 201 (backlog.md item 66). ⚠ Asserted <b>from a
     * caller with no profile</b>, because both refusals are 400 and the ordering is what decides which
     * message a clinician reads: the useless one — "create your profile first" — would otherwise win.
     */
    @Test
    void theGenericUploadRefusesTheAvatarTypeAndNamesTheAvatarPath() throws Exception {
        assertThat(profileRepository.findAll()).isEmpty();

        restMockMvc
            .perform(
                post(OWN_DOCUMENT_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"selfie.jpg\",\"type\":\"AVATAR\",\"dataContentType\":\"image/jpeg\",\"data\":\"" +
                        Base64.getEncoder().encodeToString(jpeg()) +
                        "\"}"
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("/api/personal-document/avatar"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    /**
     * ⛔ <b>The plural admin maintenance {@code POST} refuses it too.</b>
     *
     * <p>Sharper there than on the singular path, because that handler binds the whole entity: it
     * chooses its own {@code profileId}, applies no ceiling, no allow-list, no magic-byte check and
     * <b>no metadata strip</b> — so it is the one route that could write an unscrubbed photograph onto
     * somebody else's profile. Driven as {@code ROLE_ADMIN}, which is a member of
     * {@code CLINICAL_MUTATION} and is what that surface is for.
     */
    @Test
    void thePluralAdminCreateRefusesTheAvatarType() throws Exception {
        restMockMvc
            .perform(
                post("/api/personal-documents")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"selfie.jpg\",\"type\":\"AVATAR\",\"profileId\":\"somebody-else\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("/api/personal-document/avatar"));

        assertThat(personalDocumentRepository.findAll()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // ⛔ An avatar counts towards nothing — through the real predicates, three gates
    // ---------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>An avatar satisfies neither {@code REQ_PHOTO} site.</b>
     *
     * <p>{@code REQ_PHOTO} is {@code anyMatch(type == PASSPHOTO)} in two places — the meter and the
     * submit gate — and <b>both are driven here over HTTP rather than reasoned about</b>: the meter
     * through {@code GET /api/onboarding/progress}, which must still report {@code photo: false} with
     * an avatar uploaded, and the submit gate through the real button, which must still refuse. This is
     * the reason {@code AVATAR} had to be its own value: stored as {@code PASSPHOTO} a selfie would
     * carry an application into {@code CREDENTIAL_REVIEW}.
     */
    @Test
    void anAvatarSatisfiesNeitherThePhotoRequirementNorTheSubmitGate() throws Exception {
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(CALLER_ACCOUNT));
        restMockMvc
            .perform(
                post("/api/professional-application")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true}")
            )
            .andExpect(status().isCreated());
        // Everything step 3 asks for EXCEPT the passport photograph.
        personalDocumentRepository.save(CompleteOnboardingFixture.document(profile, DocumentType.CERTIFICATE, null));
        personalDocumentRepository.save(
            CompleteOnboardingFixture.document(profile, DocumentType.LICENSE, CompleteOnboardingFixture.currentLicenseExpiry())
        );
        personalDocumentRepository.save(CompleteOnboardingFixture.document(profile, DocumentType.GHANACARD, null));

        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());

        // 1. The meter still reports the photo requirement unsatisfied, and the whole meter incomplete.
        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requirements[?(@.key == 'photo')].done").value(false))
            .andExpect(jsonPath("$.complete").value(false));

        // 2. And the submit gate still refuses.
        restMockMvc
            .perform(
                put("/api/professional-application/me/submit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"authority\":\"ROLE_NURSE\",\"agreed\":true}")
            )
            .andExpect(status().isBadRequest());

        // 3. A real PASSPHOTO then does satisfy it — so the refusals above are the predicate answering,
        //    not a walk that was never capable of passing.
        personalDocumentRepository.save(CompleteOnboardingFixture.document(profile, DocumentType.PASSPHOTO, null));
        restMockMvc
            .perform(get("/api/onboarding/progress"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requirements[?(@.key == 'photo')].done").value(true));
    }

    /**
     * ⛔⛔ <b>A {@code PENDING} avatar does not block approval — the defect this exclusion exists for.</b>
     *
     * <p>{@code OnboardingService.allLiveDocumentsVerified} is an {@code allMatch(VERIFIED)} over a
     * profile's live documents, behind the 409 in {@code requireAllMandatoryDocumentsVerified}. An
     * avatar left in that set would have made it answer {@code false} <b>for ever</b>, because nothing
     * in the estate can verify an avatar: no reviewer surface lists one, so no reviewer can give it a
     * verdict. <b>Every clinician who uploaded a photograph would have become unapprovable</b>, with no
     * action open to anyone.
     *
     * <p>⚠ <b>Asserted in both directions in one walk.</b> An unverified <em>credential</em> is still
     * refused with the same 409 first, so a green approval below cannot come from a gate that had
     * stopped refusing anything at all. That is the only way to tell "the avatar is excluded" from "the
     * approval gate is broken".
     */
    @Test
    void aPendingAvatarDoesNotBlockApprovalWhileAPendingCredentialStillDoes() throws Exception {
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(CALLER_ACCOUNT));
        ProfessionalApplication application = applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(CALLER_ACCOUNT, ProfileStatus.CREDENTIAL_REVIEW).profileId(profile.getId())
        );
        CompleteOnboardingFixture.mandatoryDocuments(profile).forEach(personalDocumentRepository::save);

        // Red first: four documents, all PENDING, so approval is refused.
        decide(application).andExpect(status().isConflict());

        // Verify the credentials — and upload an avatar, which no reviewer will ever verify.
        personalDocumentRepository
            .findByProfileId(profile.getId())
            .forEach(document -> personalDocumentRepository.save(document.verificationStatus(VerificationStatus.VERIFIED)));
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());
        assertThat(onlyAvatar().getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);

        // Green: the PENDING avatar is not a credential awaiting a verdict.
        decide(application).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    // ---------------------------------------------------------------------------------------------
    // The lists an avatar must not appear in
    // ---------------------------------------------------------------------------------------------

    /**
     * The avatar is absent from the clinician's own credential list.
     *
     * <p>Not a leak — the row is the caller's own — but that list drives the documents tab beside a
     * certificate and a licence, carrying a {@code PENDING} status it will never lose, which on that
     * screen reads as a credential still awaiting a reviewer. ⚠ A credential is uploaded in the same
     * test, so the assertion is "the avatar is filtered" and not "the list is empty".
     */
    @Test
    void theAvatarIsAbsentFromTheOwnDocumentList() throws Exception {
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());
        Profile profile = profileRepository.findByAccountId(CALLER_ACCOUNT).orElseThrow();
        personalDocumentRepository.save(CompleteOnboardingFixture.document(profile, DocumentType.CERTIFICATE, null));

        restMockMvc
            .perform(get(OWN_DOCUMENT_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].type").value("CERTIFICATE"));

        // The row is still there — filtered from a list, not deleted from the collection.
        assertThat(avatars()).hasSize(1);
    }

    /**
     * ⛔ <b>And from the reviewer's document list</b>, which is the half that is a privacy point as
     * well as a presentation one.
     *
     * <p>{@code GET /api/professional-application/{id}/documents} is what the review screen renders,
     * and the byte stream behind every row in it would serve an administrator a photograph of the
     * clinician they are credentialing. It is also the client mirror of the approval gate — that
     * screen checks "every document verified" over this list — so a {@code PENDING} avatar here would
     * block approval from the browser after the server had stopped blocking it.
     */
    @Test
    void theAvatarIsAbsentFromTheReviewerDocumentList() throws Exception {
        Profile profile = profileRepository.save(CompleteOnboardingFixture.completeProfile(CALLER_ACCOUNT));
        ProfessionalApplication application = applicationRepository.save(
            CompleteOnboardingFixture.consentedApplication(CALLER_ACCOUNT, ProfileStatus.CREDENTIAL_REVIEW).profileId(profile.getId())
        );
        personalDocumentRepository.save(CompleteOnboardingFixture.document(profile, DocumentType.CERTIFICATE, null));
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());

        restMockMvc
            .perform(get("/api/professional-application/{id}/documents", application.getId()).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].type").value("CERTIFICATE"));
    }

    // ---------------------------------------------------------------------------------------------
    // Serving the bytes — the existing owner-or-reviewer stream, unchanged and asserted here
    // ---------------------------------------------------------------------------------------------

    /**
     * ⭐ <b>The avatar is served by the existing {@code assertOwnerOrReviewer} stream, and that gate is
     * the reason the bytes live in this service at all.</b>
     *
     * <p>Row 232 settled on {@code api/} because the avatar has to be readable by <em>the owner, or a
     * reviewer</em> — which no authority list on a static path can express. ⛔ There is no
     * {@code permitAll()} and no resource handler anywhere in this change, so these four statuses are
     * the whole access story for a clinician's photograph: owner 200, stranger 403, administrator 200,
     * unknown id 404 <b>before</b> the ownership check, so an id cannot be probed for existence.
     */
    @Test
    void theAvatarIsStreamedToItsOwnerAndToAReviewerAndToNobodyElse() throws Exception {
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());
        String id = onlyAvatar().getId();

        restMockMvc.perform(get(OWN_DOCUMENT_URL + "/{id}/content", "no-such-avatar").with(someoneElse())).andExpect(status().isNotFound());
        restMockMvc
            .perform(get(OWN_DOCUMENT_URL + "/{id}/content", id))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.IMAGE_JPEG_VALUE))
            .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(onlyAvatar().getData()));
        restMockMvc.perform(get(OWN_DOCUMENT_URL + "/{id}/content", id).with(someoneElse())).andExpect(status().isForbidden());
        restMockMvc.perform(get(OWN_DOCUMENT_URL + "/{id}/content", id).with(admin())).andExpect(status().isOk());
    }

    /**
     * ⛔ <b>{@code HEAD} is gated beside {@code GET}</b>, and this estate has been bitten by the
     * absence of exactly this test twice.
     *
     * <p>Spring MVC dispatches a {@code HEAD} to the {@code @GetMapping} handler, so a rule or a check
     * that only considers {@code GET} lets a body-less read fall through to whatever sits below — a
     * measured fail-open on {@code /api/profiles} (backlog.md item 143). <b>A body-less read of a
     * photograph is still a read</b>: a 200 on a stranger's {@code HEAD} answers "this person has an
     * avatar", and a 404-versus-200 difference is an existence oracle with no body needed at all.
     */
    @Test
    void headIsGatedBesideGetOnTheByteStream() throws Exception {
        postAvatar(jpeg(), MediaType.IMAGE_JPEG_VALUE).andExpect(status().isCreated());
        String id = onlyAvatar().getId();

        restMockMvc.perform(head(OWN_DOCUMENT_URL + "/{id}/content", id)).andExpect(status().isOk());
        restMockMvc.perform(head(OWN_DOCUMENT_URL + "/{id}/content", id).with(someoneElse())).andExpect(status().isForbidden());
        restMockMvc.perform(head(OWN_DOCUMENT_URL + "/{id}/content", id).with(admin())).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private ResultActions postAvatar(byte[] data, String contentType) throws Exception {
        String body = "{\"dataContentType\":\"" + contentType + "\",\"data\":\"" + Base64.getEncoder().encodeToString(data) + "\"}";
        return restMockMvc.perform(post(AVATAR_URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions decide(ProfessionalApplication application) throws Exception {
        return restMockMvc.perform(
            put("/api/professional-application/{id}/decide", application.getId())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}")
        );
    }

    private List<PersonalDocument> avatars() {
        return personalDocumentRepository.findAll().stream().filter(document -> document.getType() == DocumentType.AVATAR).toList();
    }

    private PersonalDocument onlyAvatar() {
        List<PersonalDocument> stored = avatars();
        assertThat(stored).hasSize(1);
        return stored.get(0);
    }

    /** A real, minimal JPEG — written by {@code ImageIO}, so it is one rather than looking like one. */
    private static byte[] jpeg() throws Exception {
        return encode("jpeg");
    }

    private static byte[] png() throws Exception {
        return encode("png");
    }

    private static byte[] encode(String format) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x112233);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    /**
     * A JPEG carrying an {@code APP1 Exif} segment, built by hand.
     *
     * <p>⚠ Built rather than committed as a binary fixture, so what the test asserts is absent is
     * visibly the same string this method puts in. {@code ImageIO} has no writer that emits EXIF, so
     * the segment is inserted directly after {@code SOI} — which is where a camera puts it and is a
     * well-formed JPEG that every decoder skips.
     */
    private static byte[] jpegWithExif() throws Exception {
        byte[] plain = jpeg();
        byte[] payload = ("Exif\0\0" + EXIF_MARKER + ";GPSLongitude=-0.1870;HOME").getBytes(StandardCharsets.US_ASCII);
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(payload);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /**
     * A real JPEG whose {@code SOF0} dimensions have been overwritten — a decompression bomb.
     *
     * <p>⚠ <b>Patched rather than hand-built, and the marker search asserts it found one.</b> A
     * helper that silently failed to patch would hand the test a perfectly ordinary 8×8 image, and the
     * test would pass for having validated nothing at all. {@code SOF0} is {@code FF C0} followed by a
     * 2-byte length, 1 byte of sample precision, then <b>height then width</b>, each big-endian — that
     * order is the one easy mistake here and a transposed pair would still produce a bomb, so it is
     * written out rather than left to be inferred.
     */
    private static byte[] jpegWithPatchedDimensions(int width, int height) throws Exception {
        byte[] bytes = jpeg();
        int sof = -1;
        for (int i = 0; i + 1 < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == 0xFF && (bytes[i + 1] & 0xFF) == 0xC0) {
                sof = i;
                break;
            }
        }
        assertThat(sof).as("ImageIO must emit a baseline SOF0 segment for this helper to patch").isNotNegative();
        int heightAt = sof + 5;
        bytes[heightAt] = (byte) (height >> 8);
        bytes[heightAt + 1] = (byte) height;
        bytes[heightAt + 2] = (byte) (width >> 8);
        bytes[heightAt + 3] = (byte) width;
        return bytes;
    }

    /**
     * A real PNG whose {@code IHDR} dimensions have been overwritten, with the chunk CRC recomputed.
     *
     * <p>⚠ <b>The CRC has to be fixed or the reader rejects the chunk before reading a dimension</b>,
     * which would make this test pass on a malformed-file refusal rather than on the bound. {@code IHDR}
     * is the first chunk after the 8-byte signature: 4 bytes of length, the type, then width and
     * height as big-endian ints.
     */
    private static byte[] pngWithPatchedDimensions(int width, int height) throws Exception {
        byte[] bytes = png();
        int typeAt = 12; // 8-byte signature + 4-byte length
        int dataAt = typeAt + 4;
        for (int i = 0; i < 4; i++) {
            bytes[dataAt + i] = (byte) (width >> (24 - 8 * i));
            bytes[dataAt + 4 + i] = (byte) (height >> (24 - 8 * i));
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, typeAt, 4 + 13); // the type and the 13 IHDR bytes
        long value = crc.getValue();
        int crcAt = dataAt + 13;
        for (int i = 0; i < 4; i++) {
            bytes[crcAt + i] = (byte) (value >> (24 - 8 * i));
        }
        return bytes;
    }

    /**
     * A JPEG of the given frame size that is photograph-shaped rather than flat.
     *
     * <p>⚠ A uniform fill would compress to almost nothing and prove nothing about the stored ceiling,
     * while pure noise is incompressible and is not what a camera produces. A smooth gradient with a
     * little per-pixel variation sits where a photograph does.
     */
    private static byte[] photoShapedJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255) / width;
                int g = (y * 255) / height;
                int b = ((x ^ y) & 0x3F) + 64;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpeg", out)).isTrue();
        return out.toByteArray();
    }

    /** Authenticated, with a {@code uid} claim naming nobody's profile in this service. */
    private static RequestPostProcessor someoneElse() {
        return gatewayUser("a-stranger", "ROLE_NURSE");
    }

    private static RequestPostProcessor admin() {
        return gatewayUser("reviewer", "ROLE_ADMIN");
    }

    /**
     * ⛔ <b>Unauthenticated, on every mapping this change touches.</b>
     *
     * <p>A nested class because the absence of a caller is a property of the security context and not
     * of a request, so it needs its own {@code @WithUnauthenticatedMockUser} rather than a
     * post-processor. ⚠ The byte stream is included: an avatar is not public, which is the one finding
     * of {@code avatar-storage-addendum.md} § 2 that is about exposure rather than cost, and it does
     * not depend on where the bytes ended up living.
     */
    @AutoConfigureMockMvc
    @IntegrationTest
    @WithUnauthenticatedMockUser
    static class Unauthenticated {

        @Autowired
        private MockMvc restMockMvc;

        @Test
        void refusesAnAnonymousAvatarUploadAndAnAnonymousRead() throws Exception {
            restMockMvc
                .perform(post(AVATAR_URL).contentType(MediaType.APPLICATION_JSON).content("{\"dataContentType\":\"image/jpeg\"}"))
                .andExpect(status().isUnauthorized());
            restMockMvc.perform(get(OWN_DOCUMENT_URL + "/{id}/content", "any-id")).andExpect(status().isUnauthorized());
            restMockMvc.perform(head(OWN_DOCUMENT_URL + "/{id}/content", "any-id")).andExpect(status().isUnauthorized());
        }
    }
}
