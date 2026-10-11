package net.jojoaddison.web.rest;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.Profile;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.PersonalDocumentRepository;
import net.jojoaddison.repository.ProfileRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.PersonalDocumentService;
import net.jojoaddison.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's own credentialing documents — {@code POST}/{@code GET /api/personal-document} and the
 * authorized byte stream beneath it (profile.md step 3, T2).
 *
 * <h2>⛔ Its own class, for the reason {@link OwnProfileResource} is one</h2>
 *
 * <p>The mechanical half: {@link PersonalDocumentResource} is
 * {@code @RequestMapping("/api/personal-documents")} — <b>plural</b> — and a class-level prefix cannot
 * be opted out of per handler, so the singular path cannot live there. The half that matters is that
 * the two resources answer <em>different</em> questions about who may call them and what they return:
 * that one is the generated CRUD surface, admin-shaped, and has <b>no {@code GET} mapping left</b>
 * since S1 closed (backlog.md row 226) — it carried three that returned {@code data} inline and checked
 * no ownership whatever; this one is an applicant's own upload, and it <b>never echoes the bytes
 * back</b>. ⚠ That resource's {@code PATCH} does still answer with a whole document, which is
 * backlog.md row 227 and not a read surface anybody should be pointed at.
 *
 * <p>⚠ <b>The singular path is not a tidier spelling of the plural one.</b>
 * {@code /api/personal-document} does not match {@code /api/personal-documents} and must not come to,
 * because a rule written here would widen the plural resource's remaining
 * {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} — the admin data-maintenance surface for any
 * clinician's documents. {@code ClinicalAuthorityMatrixIT} asserts that in both directions, because it
 * is a claim about Spring's pattern matching rather than about this comment.
 *
 * <h2>{@code profileId} is server-derived and the client never sends it</h2>
 *
 * <p>{@code profile.md} says {@code profileId} is "set to {@code Profile.id}", and it is set here,
 * from the caller's own profile through the {@code uid} claim. So like {@code /api/profile} this
 * endpoint takes no subject at all and <b>cannot name anyone but the caller</b>, which is why
 * {@code .authenticated()} is the right gate: an applicant holds {@code ROLE_USER} and nothing else
 * until an administrator assigns one, so an authority check would constrain nothing while the admin
 * gate would lock out the people step 3 exists for. The estate's rule is that the subject decides the
 * gate, not the resource (workspace {@code CLAUDE.md} § "How products read each other").
 *
 * <h2>The security rules this needs, in two repositories (T0)</h2>
 *
 * <p>{@code api/SecurityConfiguration} carries {@code /api/personal-document} <em>and</em>
 * {@code /api/personal-document/**} in the island beside {@code /api/onboarding/**}, and
 * {@code gateway/SecurityConfiguration} mirrors both. <b>Both repositories are required and neither
 * is sufficient</b>: without the service rule the {@code POST} falls through to
 * {@code POST /api/** -> CLINICAL_MUTATION} and every applicant is 403'd uploading their own licence;
 * without the gateway rule the request is refused at {@code /services/** -> CLINICAL_AND_ADMIN}
 * before the service is reached, <em>and the refusal is attributed to the service</em>.
 *
 * <p>⚠ <b>The prefix form is needed here and was not on {@code /api/profile}</b>, because this path
 * genuinely has a sub-resource — {@code /{id}/content}. That is a widening and is treated as one:
 * {@code ClinicalAuthorityMatrixIT} and {@code ServicesRouteAuthorizationIT} both assert that the
 * prefix stops at the singular path and does not reach the plural CRUD surface beside it.
 *
 * <p>⚠ <b>The matchers are method-agnostic, so {@code HEAD} is covered.</b> Spring MVC dispatches a
 * {@code HEAD} to the {@code @GetMapping} handler, and a rule scoped to {@code HttpMethod.GET} lets
 * it fall through to whatever sits below — a measured fail-open on {@code /api/profiles}, caught in
 * review (backlog.md item 143). A body-less read of a document list is still a read.
 *
 * <p><b>The {@code @PreAuthorize} on each handler is the second layer</b> and both are kept, as
 * {@code api/} PR #55 settled: method security intercepts the invocation whatever the verb, and the
 * filter chain covers a handler added here later. ⛔ Do not delete either on the strength of the
 * other's comment.
 *
 * <h2>What moved here, and what deliberately did not</h2>
 *
 * <p>The upload, the own-document list and the content stream came from
 * {@code OnboardingDocumentResource} on {@code /api/onboarding/documents}, and the applicant-facing
 * mappings there are gone — this is the only path a clinician uploads through now.
 *
 * <h2>The avatar rides this base, and needs no new security rule (row 232)</h2>
 *
 * <p>{@code POST /api/personal-document/avatar} is the estate's only avatar upload. It is here rather
 * than in {@code gateway/} even though {@code User.imageUrl} is the gateway's field, because the
 * decision turned on <em>serving</em> rather than storing: the bytes have to be readable by <b>the
 * owner, or a reviewer</b>, which is {@link #assertOwnerOrReviewer} on {@link #streamContent} below
 * and which a single authority list on a static path cannot express at all. The client uploads here,
 * then writes the content path to {@code User.imageUrl} through the gateway's
 * {@code PUT /api/account}; <b>no call passes between the two services.</b>
 *
 * <p>⚠ <b>No {@code SecurityConfiguration} line was added and that is checked rather than assumed.</b>
 * The island rule above is {@code "/api/personal-document", "/api/personal-document/**"} and the
 * prefix form already matches {@code /avatar} — matched <em>before</em>
 * {@code POST /api/** -> CLINICAL_MUTATION}, since the first matching rule wins — so an applicant
 * holding only {@code ROLE_USER} is served. ⛔ That is required and not a convenience: every clinical
 * authority list excludes {@code ROLE_USER}, so "tightening" this upload to one would 403 exactly the
 * applicants step 1 is written for. The handler carries the matching
 * {@code @PreAuthorize("isAuthenticated()")}, both layers as ever, and both method-agnostic.
 *
 * <p>⭐ <b>The reviewer's half caught up in T3 and is now on this base too</b>:
 * {@code PUT /api/personal-document/{id}/verify} and {@code .../reject}, both {@code ROLE_ADMIN},
 * in {@link PersonalDocumentReviewResource} — the same class, renamed with its path. Two classes
 * still serve this one base, and that is now a split <b>by gate</b> rather than the transitional
 * state this paragraph used to describe: this one is {@code .authenticated()} and never echoes
 * document bytes back, that one is the administrator's verdict.
 */
@RestController
@RequestMapping("/api/personal-document")
public class OwnPersonalDocumentResource {

    private static final Logger log = LoggerFactory.getLogger(OwnPersonalDocumentResource.class);

    /**
     * The application's own ceiling, measured on the <b>decoded</b> document.
     *
     * <p>⚠ <b>The ceiling is a chain, and switching this endpoint to the specified JSON body changed
     * which rungs are in it.</b> On the retired multipart path it was three deep: this 5 MB check (a
     * clear 400 naming the limit) → {@code spring.servlet.multipart.max-file-size: 6MB} (an opaque
     * {@code MaxUploadSizeExceededException}, mapped to 413 by {@code ExceptionTranslator} so it does
     * not read as a server fault) → nginx's 8 MB (a bare 413 with no body at all). <b>The middle rung
     * no longer applies</b>: a JSON request is not a multipart one, so the servlet container's
     * multipart parser never runs and that property is inert for this path. The chain is now this
     * check and then nginx.
     *
     * <p>⚠ <b>And base64 moves where nginx bites.</b> {@code data} arrives as base64, which is 4/3 of
     * the document, so nginx's 8 MB cap on the raw body is reached at roughly <b>6 MB of document</b>
     * — above this limit, so the clear message still wins for everything between 5 and ~6 MB, which
     * is the band a clinician's camera actually produces. Past that the answer is nginx's bare 413.
     * ⛔ Do not raise this constant without re-deriving that figure: at 6 MB the two rungs coincide
     * and every oversize upload would start answering with no body at all.
     */
    private static final long MAX_BYTES = 5_000_000L;

    /**
     * The avatar's own ceiling, measured on the <b>decoded</b> image — and the rung below
     * {@link #MAX_BYTES} rather than a second opinion about it.
     *
     * <p>⚠ <b>The arithmetic, because a ceiling nobody can see from the others is what row 226's
     * lesson is about.</b> Six rungs, outermost first — ⛔ <b>and this table listed only four until
     * review, which is how the two biggest holes stayed invisible: every rung here was about
     * COMPRESSED BYTES, and the two that were missing are about the DECODED RASTER and the
     * RE-ENCODED OUTPUT.</b> A table of ceilings that silently covers one quantity is worse than no
     * table, because it reads as exhaustive.
     *
     * <table>
     *   <caption>Which rung bites, and with what answer</caption>
     *   <tr><th>rung</th><th>on</th><th>reached at</th><th>the caller sees</th></tr>
     *   <tr><td>nginx {@code client_max_body_size 8m}</td><td>the <b>raw</b> body</td>
     *       <td>~6 MB of image, since base64 is 4/3</td><td>a bare 413, no body</td></tr>
     *   <tr><td>{@link #MAX_BYTES} = 5 MB</td><td>decoded bytes, any document</td>
     *       <td>5 MB</td><td>a 400 naming 5 MB</td></tr>
     *   <tr><td><b>this constant = 2 MB</b></td><td>decoded bytes, an avatar</td>
     *       <td><b>2 MB — the first rung an avatar normally reaches</b></td>
     *       <td>a 400 naming 2 MB</td></tr>
     *   <tr><td>⭐ {@link #MAX_PIXELS} = 16 Mpx</td><td>the <b>decoded raster</b>, read from the header
     *           before anything is allocated</td>
     *       <td><b>691 bytes can reach it</b> — a header may claim any size</td>
     *       <td>a 400 naming the megapixel limit</td></tr>
     *   <tr><td>⭐ {@link #AVATAR_RENDER_EDGE} = 1024 px, then this constant again</td>
     *       <td>the <b>re-encoded output</b></td>
     *       <td>when a re-encode amplifies past 2 MB</td>
     *       <td>a 400 saying so once re-encoded</td></tr>
     *   <tr><td>{@link #normalizeImage}</td><td>whether the bytes are an image at all</td>
     *       <td>any size</td><td>a 400 saying the image could not be read</td></tr>
     * </table>
     *
     * <p>⛔ <b>Do not raise this above {@link #MAX_BYTES} and do not remove the comment.</b> At 5 MB
     * the two application rungs coincide and the narrower message stops being reachable; past ~6 MB
     * the answer becomes nginx's bare 413 with nothing in it, which is the unreadable middle rung row
     * 226 recorded. 2 MB is chosen because an avatar is rendered into a sidebar circle — a modern
     * phone camera's 3–8 MB original is two orders of magnitude more data than that pixel count needs
     * — and because a tighter cap is the cheapest limit on what this collection grows by for a file
     * nobody reviews.
     *
     * <p>⚠ <b>This rung alone constrains nothing about memory or about what is stored.</b> Compressed
     * bytes and pixels are independent — see {@link #MAX_PIXELS} for the 691-byte body that asked for
     * 400 megapixels — and re-encoding is not size-preserving, so the two starred rungs are not
     * refinements of this one. Removing either re-opens a hole this one cannot see.
     */
    private static final long AVATAR_MAX_BYTES = 2_000_000L;

    private static final Set<String> ALLOWED_TYPES = Set.of(
        MediaType.APPLICATION_PDF_VALUE,
        MediaType.IMAGE_PNG_VALUE,
        MediaType.IMAGE_JPEG_VALUE
    );

    /**
     * ⚠ <b>Narrower than {@link #ALLOWED_TYPES} on purpose, and it is a subset of it.</b> A PDF is a
     * credential scan and is not an avatar; {@code image/jpeg} and {@code image/png} are what
     * {@link #magicBytesMatch} already verifies and what {@link #normalizeImage} can re-encode without
     * a new library. WebP is deliberately absent — it would need new magic bytes here, a new branch
     * there, and no client in the estate produces one ({@code mobile/}'s camera pipeline re-encodes to
     * JPEG, which is why HEIC never reaches this service either).
     */
    private static final Set<String> AVATAR_TYPES = Set.of(MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE);

    /**
     * The ceiling on the <b>decoded raster</b>, which is a different quantity from every byte ceiling
     * above and is the one an image format lets a caller lie about.
     *
     * <h2>⛔ Why a byte limit cannot express this (found in review)</h2>
     *
     * <p>A compressed image declares its dimensions in a header, and a decoder believes it. Three cases
     * measured on the production heap ({@code -Xmx384m} per {@code deploy/prod-server/compose.yml}):
     *
     * <table>
     *   <caption>What a small body could do before this constant existed</caption>
     *   <tr><th>body</th><th>claims</th><th>outcome</th></tr>
     *   <tr><td>388,871 bytes of PNG</td><td>20000×20000</td>
     *       <td>{@code OutOfMemoryError} wrapped in {@code IIOException} → 400, <b>after</b> a 400 MB
     *           allocation attempt</td></tr>
     *   <tr><td>218,780 bytes of PNG</td><td>15000×15000</td>
     *       <td>⛔ <b>decoded SUCCESSFULLY</b>, holding 222 MB of a 384 MB heap for ~1.7 s — a ~1000×
     *           amplifier with <b>nothing wrong for any handler to catch</b>. Four concurrent requests
     *           OOMed three of four JVMs</td></tr>
     *   <tr><td><b>691 bytes</b> of JPEG, {@code SOF0} patched</td><td>20000×20000</td>
     *       <td>⛔ bare {@code java.lang.OutOfMemoryError} — an {@link Error}, so it walked past
     *           {@code catch (IOException)} → <b>500</b></td></tr>
     * </table>
     *
     * <p>⭐ <b>The middle row is why the fix is a bound and not a wider {@code catch}</b>: a successful
     * decode raises nothing.
     *
     * <h2>The arithmetic, and the trap in the obvious number</h2>
     *
     * <p>{@code BufferedImage} is 4 bytes per pixel in both types used here, so the decoded raster at
     * 16 Mpx is ≈ 64 MB — but the <b>measured peak through the whole sequence is 92 MB, 24% of the
     * 384 MB production heap</b> (the raster plus the render target plus Java2D's intermediate),
     * transient. The raster figure alone is the one a capacity reader would wrongly budget from, which
     * is this table's own defect class one layer down. ⚠ <b>12 Mpx was the tempting bound
     * and would have rejected the exact image it was chosen to admit</b>: a mainstream phone's "12
     * megapixel" frame is 4032×3024 = <b>12,192,768</b> pixels, which is more than 12,000,000. 16 Mpx
     * clears that and 4608×3456 (15.9 Mpx) beside it.
     *
     * <p>Above this the answer is a 400 naming the megapixel limit — the one rung that reads the
     * header and allocates nothing. ⚠ A 48 Mpx phone original is refused, which is correct and is also
     * the band {@link #AVATAR_MAX_BYTES} usually catches first; T8's client downscales before upload.
     */
    private static final long MAX_PIXELS = 16_000_000L;

    /**
     * The longest edge of the <b>stored</b> avatar, in pixels.
     *
     * <p>Chosen for what the image is <em>for</em>: it renders in {@code web/}'s sidebar circle and in
     * {@code mobile/}'s header, tens of pixels across. 1024 covers the largest of those at 3× device
     * pixel ratio with room to spare, and is a round number a reader can reason about.
     *
     * <p>⛔ <b>It is also what makes {@link #AVATAR_MAX_BYTES} true of the stored row.</b> Re-encoding
     * amplifies — measured 1.9× on a low-quality JPEG and 5.1× on a sub-filtered PNG — so without a
     * downscale the bytes checked on arrival are not the bytes stored. A 12 Mpx photograph lands at
     * roughly 1024×768 and a couple of hundred kilobytes.
     */
    private static final int AVATAR_RENDER_EDGE = 1024;

    private final PersonalDocumentRepository personalDocumentRepository;
    private final ProfileRepository profileRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final PersonalDocumentService personalDocumentService;
    private final ProfileService profileService;

    public OwnPersonalDocumentResource(
        PersonalDocumentRepository personalDocumentRepository,
        ProfileRepository profileRepository,
        DomainEventPublisher domainEventPublisher,
        PersonalDocumentService personalDocumentService,
        ProfileService profileService
    ) {
        this.personalDocumentRepository = personalDocumentRepository;
        this.profileRepository = profileRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.personalDocumentService = personalDocumentService;
        this.profileService = profileService;
    }

    /**
     * The upload body — {@code profile.md}'s {@code PersonalDocument} model, and nothing else.
     *
     * <h2>⛔ An allow-list, not the entity with a refusal map beside it</h2>
     *
     * <p>{@link PersonalDocument} carries nineteen fields and <b>fourteen of them are the server's or
     * the reviewer's</b>: {@code id}, {@code profileId}, {@code sha256Checksum}, {@code sizeBytes},
     * {@code createdDate}, {@code modifiedDate}, {@code lastModifiedBy}, {@code verificationStatus},
     * {@code verifiedBy}, {@code verifiedAt}, {@code rejectionReason}, {@code supersededAt},
     * {@code supersededByDocumentId}. Binding the entity would make every one of them client-settable
     * and leave a deny-list to maintain — an applicant posting
     * {@code "verificationStatus": "VERIFIED"} would approve their own credential. <b>A type carrying
     * only what the specification names cannot acquire a field through somebody else's edit</b>, which
     * is the argument T4 settled on {@code OwnAccountDTO} and the reason {@code PATCH_REFUSED_FIELDS}
     * exists on the plural resource rather than here.
     *
     * <p>Five of these six components are {@code profile.md}'s model table verbatim, less
     * {@code profileId} which the specification itself assigns to the server ("set to
     * {@code Profile.id}"). {@code otherLabel} and {@code expiryDate} are already fields on
     * {@link PersonalDocument} and are what two of the refusals below are about; they are not in the
     * model table and the table says of the sibling model that it is "not exhaustive".
     *
     * <p>⚠ {@code supersedesDocumentId} is <b>not</b> a field on the document and is not stored under
     * that name — it names one of the caller's own live rows to archive, and the pair that records the
     * outcome is {@code supersededAt}/{@code supersededByDocumentId} on <em>that</em> row. It rides
     * here rather than as a query parameter so one request carries one intent.
     *
     * @param name the file name, as the clinician's own label for the row.
     * @param type which credential this is.
     * @param data the document itself, base64 on the wire.
     * @param dataContentType its MIME type, which {@link #magicBytesMatch} then holds it to.
     * @param otherLabel required when {@code type} is {@code OTHER}.
     * @param expiryDate required when {@code type} is {@code LICENSE}.
     * @param supersedesDocumentId one of the caller's own live documents this one replaces, or
     *     {@code null} to add without replacing anything.
     */
    public record PersonalDocumentUpload(
        String name,
        DocumentType type,
        byte[] data,
        String dataContentType,
        String otherLabel,
        LocalDate expiryDate,
        String supersedesDocumentId
    ) {}

    /**
     * {@code POST /api/personal-document} : uploads one credential for the caller.
     *
     * <h2>The body is {@code profile.md}'s model, with {@code data} as the uploaded file</h2>
     *
     * <p>⚠ <b>This path was {@code multipart/form-data} until T2 and the specification's shape is what
     * ships.</b> The consequence worth knowing is in {@link #MAX_BYTES}: base64 is 4/3 of the
     * document, so the ceiling chain lost its middle rung and nginx's bare 413 now arrives at about
     * 6 MB of document rather than 8. The validations are unaffected — a magic-byte check reads
     * decoded bytes just as well as it read a part — and all seven refusals below are the ones the
     * multipart handler made.
     *
     * <h2>Seven refusals, and the last one is step 3's dependency on step 2</h2>
     *
     * <p>Six are in {@link #validate}: empty, over {@link #MAX_BYTES}, a content type outside the
     * allowlist, <b>content that does not match the type it declares</b>, {@code OTHER} without a
     * label, and {@code LICENSE} without an expiry date. The seventh is {@link #ownProfile}: a
     * caller with no {@link Profile} is told to create one first, because
     * {@code PersonalDocument.profileId} is what every reader joins on and there is nothing to join
     * to. ⚠ A declared content type is a claim by the client, so the magic bytes are checked
     * separately — the allowlist alone would accept anything labelled {@code application/pdf}.
     *
     * <h2>What the server sets and the client cannot</h2>
     *
     * <p>{@code profileId}, {@code sha256Checksum}, {@code sizeBytes},
     * {@code verificationStatus = PENDING} and {@code createdDate} are all derived here, and
     * {@link PersonalDocumentUpload} is why "cannot" is the right word rather than "does not" — there
     * is no component on that record by which a caller could claim a verdict no reviewer has given.
     *
     * @param upload the document to store.
     * @return the persisted document, with its bytes removed.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PersonalDocument> upload(@RequestBody PersonalDocumentUpload upload) {
        refuseAvatarType(upload.type());
        Profile profile = ownProfile();
        byte[] bytes = upload.data() == null ? new byte[0] : upload.data();
        validate(bytes, upload.dataContentType(), upload.type(), upload.otherLabel(), upload.expiryDate());

        PersonalDocument document = new PersonalDocument()
            .name(upload.name())
            .profileId(profile.getId())
            .type(upload.type())
            .otherLabel(upload.otherLabel())
            .expiryDate(upload.expiryDate())
            .sha256Checksum(sha256(bytes))
            .sizeBytes((long) bytes.length)
            .verificationStatus(VerificationStatus.PENDING)
            .createdDate(LocalDate.now());
        document.setData(bytes);
        document.setDataContentType(upload.dataContentType());
        // Archives the row the clinician says this one replaces, rather than piling up beside it
        // (backlog.md item 20). `supersedesDocumentId` is optional and names one of the caller's own
        // live documents: an upload that names nothing simply adds. The server does not infer the
        // replacement, because it cannot — a second certificate and a renewed one are the same
        // request. Superseding is applied on this path and not on the generated
        // /api/personal-documents CRUD surface: this is where a clinician renews, whereas that one is
        // an admin data-maintenance surface.
        PersonalDocument saved = personalDocumentService.saveSuperseding(document, upload.supersedesDocumentId());
        log.debug("Personal document {} ({} bytes) uploaded for profile {}", saved.getId(), bytes.length, profile.getId());
        domainEventPublisher.publishEntityCreated(
            "PersonalDocument",
            saved.getId(),
            profile.getAccountId(),
            SecurityUtils.getCurrentUserLogin().orElse("system")
        );
        // Never echo the bytes back in the create response.
        saved.setData(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * The avatar upload body — two components, because an avatar has no label and no expiry.
     *
     * <p>⚠ <b>{@code type} is not a component and cannot be</b>: this path stores
     * {@link DocumentType#AVATAR} and nothing else, so a {@code type} on the wire would be a field the
     * server must ignore, which is the deny-list shape {@link PersonalDocumentUpload}'s javadoc argues
     * against. {@code name} is likewise absent and derived from the content type — it is the
     * clinician's label for a row that no list shows them, so a client-supplied filename here would be
     * free text with no reader.
     *
     * @param data the image, base64 on the wire.
     * @param dataContentType its MIME type, held to {@link #AVATAR_TYPES} and then to the magic bytes.
     */
    public record AvatarUpload(byte[] data, String dataContentType) {}

    /**
     * {@code POST /api/personal-document/avatar} : replaces the caller's profile photograph.
     *
     * <h2>Why its own handler rather than {@code type: AVATAR} on the generic {@code POST}</h2>
     *
     * <p>Four things differ, and each would otherwise become a conditional on the shared path: a
     * narrower allow-list ({@link #AVATAR_TYPES}, no PDF), a lower ceiling
     * ({@link #AVATAR_MAX_BYTES}), <b>no {@code expiryDate} or {@code otherLabel}</b> — neither field
     * means anything about a photograph — and <b>automatic replacement</b> rather than the explicit
     * {@code supersedesDocumentId} a renewal names. The generic {@code POST} therefore <b>refuses</b>
     * {@code AVATAR} and names this path, which is the precedent {@code POST /api/profiles} set when
     * it began naming the owner path instead of quietly storing a profile belonging to nobody
     * (backlog.md item 66).
     *
     * <h2>⭐ It creates the caller's profile if there is not one — step 1 precedes step 2</h2>
     *
     * <p>{@link #ownProfile} refuses a caller with no {@link Profile}, and must, because a credential
     * has nothing to be filed against. <b>An avatar cannot use that refusal</b>: {@code imageUrl} is a
     * step-1 field and step 1 runs first, so the dialog would open on an empty {@code imageUrl}, the
     * upload would 400, and the dialog would reopen for ever. {@code ProfileService} has the house
     * shape for this — see {@code adoptOrCreateOwnProfile}, which adopts the row it finds by
     * {@code accountId} and otherwise saves one holding nothing else.
     *
     * <h2>⛔ The metadata is stripped, and the raster is bounded before it is allocated</h2>
     *
     * <p>A phone photograph carries EXIF, and EXIF carries GPS coordinates. This estate's payload rule
     * is <i>identifiers and metadata only</i>, and <b>a clinician's home address arriving inside their
     * avatar is the discovery this decision pre-empts</b> — it would be served, in full, to every
     * reviewer by the byte stream below. {@link #normalizeImage} reads the image through
     * {@code ImageIO} and writes it back in the same format, which keeps no ancillary data: JPEG keeps
     * its JFIF header and loses every {@code APP1} segment, PNG loses {@code eXIf} and {@code tEXt}.
     * ⭐ <b>The re-encode doubles as proof the bytes are an image</b> rather than something merely
     * beginning with the right four bytes, so it is a validation as well as a scrub.
     *
     * <p>⛔ <b>And every refusal on that path is a 400 — which was NOT true until review.</b> A
     * 691-byte JPEG whose {@code SOF0} claimed 20000×20000 answered <b>500</b>, because the
     * {@code OutOfMemoryError} a 400-megapixel allocation raises is an {@link Error} and not an
     * {@code IOException}; worse, a 218 KB PNG claiming 15000×15000 <em>succeeded</em>, holding 222 MB
     * of the 384 MB production heap. {@link #MAX_PIXELS} is now read from the header first, so an
     * oversized image is refused before a raster exists. The output is also downscaled to
     * {@link #AVATAR_RENDER_EDGE} and re-checked against {@link #AVATAR_MAX_BYTES}, because a
     * re-encode can <em>grow</em> a file and the arriving byte count said nothing about the stored one.
     *
     * <h2>One live avatar per profile, by deletion rather than by supersede</h2>
     *
     * <p>The previous {@code AVATAR} row is <b>deleted outright</b>. The alternative — the
     * {@code supersededAt}/{@code supersededByDocumentId} archive every credential gets — was rejected
     * for the reason that archive exists: a superseded licence is <i>evidence of what a clinician held
     * while they were treating patients</i> (backlog.md item 20), and nobody will ever need to know
     * which photograph of themselves a nurse had in March. Keeping them would accumulate an unbounded
     * series of a person's face, reviewable by every administrator through the stream below, at storage
     * cost, with no reader anywhere in the estate. ⚠ So an avatar is the one document type on which
     * {@code saveSuperseding} is deliberately <em>not</em> used.
     *
     * <h2>What the client does with the answer</h2>
     *
     * <p>The saved row with {@code data} nulled, 201 — the same contract as the generic upload. The
     * client builds {@code <base>/api/personal-document/{id}/content} itself and writes it to
     * {@code User.imageUrl} through the gateway's {@code PUT /api/account}, which is the whole of the
     * cross-service story: <b>the gateway stores a 256-character string and never calls this
     * service</b>. ⛔ No URL field is invented here; a second spelling of a path the client already has
     * to know is a second thing to keep in step.
     *
     * @param upload the image to store.
     * @return the persisted avatar row, with its bytes removed.
     */
    @PostMapping("/avatar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PersonalDocument> uploadAvatar(@RequestBody AvatarUpload upload) {
        // Not ownProfile(): step 1 runs before step 2, so this is the one upload on this resource that
        // may legitimately arrive before a profile exists. See the heading above.
        Profile profile = profileService.adoptOrCreateOwnProfile(currentAccountId());
        byte[] bytes = upload.data() == null ? new byte[0] : upload.data();
        validateAvatar(bytes, upload.dataContentType());
        // Bounds the raster from the header, decodes, downscales, re-encodes, and refuses if the
        // output still exceeds the ceiling. The order matters and is argued on the method.
        byte[] stripped = normalizeImage(bytes, upload.dataContentType());

        // Deleted, not archived — the argument is in this method's javadoc. Done before the save so a
        // failure here cannot leave two live avatars; the inverse order would.
        List<PersonalDocument> previous = personalDocumentRepository
            .findByProfileId(profile.getId())
            .stream()
            .filter(document -> document.getType() == DocumentType.AVATAR)
            .toList();
        previous.forEach(document -> personalDocumentRepository.deleteById(document.getId()));

        PersonalDocument document = new PersonalDocument()
            .name(MediaType.IMAGE_PNG_VALUE.equals(upload.dataContentType()) ? "avatar.png" : "avatar.jpg")
            .profileId(profile.getId())
            .type(DocumentType.AVATAR)
            // Of the stripped bytes, which are the bytes stored — a checksum of what arrived would
            // describe a file this service deliberately did not keep.
            .sha256Checksum(sha256(stripped))
            .sizeBytes((long) stripped.length)
            // PENDING like every other row, and it reaches no reviewer: an avatar is filtered out of
            // the own-document list and out of the reviewer's list, and
            // OnboardingService.allLiveDocumentsVerified excludes the type outright — without that
            // last exclusion a PENDING avatar would block approval for ever, since nothing can ever
            // verify it. ⛔ A status of VERIFIED was the alternative and is a worse lie: it would read
            // as a credential somebody checked.
            .verificationStatus(VerificationStatus.PENDING)
            .createdDate(LocalDate.now());
        document.setData(stripped);
        document.setDataContentType(upload.dataContentType());
        PersonalDocument saved = personalDocumentRepository.save(document);
        log.debug("Avatar {} ({} bytes stored, {} received) for profile {}", saved.getId(), stripped.length, bytes.length, profile.getId());
        domainEventPublisher.publishEntityCreated(
            "PersonalDocument",
            saved.getId(),
            profile.getAccountId(),
            SecurityUtils.getCurrentUserLogin().orElse("system")
        );
        saved.setData(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * {@code GET /api/personal-document} : every document this account has uploaded.
     *
     * <p><b>Archived rows included</b> — a clinician's credential history is theirs to read, and
     * hiding a superseded row would make a renewal look like a deletion. {@code supersededAt} is on
     * the wire so the client can label it, and {@code isLiveDocument} on the web side is the single
     * reader of that field.
     *
     * <p>⛔ <b>{@code data} is nulled on every row.</b> A thumbnail is a second request to
     * {@link #streamContent}, which is the one place the bytes leave this service and the one place
     * ownership is checked. The list would otherwise publish every identity document this account
     * holds to anything that could read the list at all — which is precisely the shape the plural CRUD
     * surface's {@code GET}s had until S1 deleted them (backlog.md row 226), on the whole collection
     * rather than on one account's rows. ⚠ The nearest thing left there is a {@code PATCH} that echoes
     * <em>one</em> document to one of six authorities (row 227), and neither that nor its eventual fix
     * is a reason to stop nulling {@code data} here.
     *
     * <p>⚠ <b>The avatar is filtered out</b> (row 232). This is the clinician's credential list and it
     * drives the documents tab beside a certificate and a licence, so a profile photograph in it is
     * noise at best — and it carries a {@code PENDING} verification status it will never lose, which
     * on that screen reads as a credential still awaiting a reviewer. The step-1 dialog reads the
     * avatar through {@code account.imageUrl} and never through this list, so nothing is hidden from
     * the surface that wants it. ⛔ It is <em>not</em> filtered because it would be a leak: the row is
     * the caller's own, which is exactly why this is a presentation decision and is argued as one.
     *
     * @return the caller's documents, without bytes and without the avatar.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<PersonalDocument> listOwnDocuments() {
        return personalDocumentRepository
            .findByProfileId(ownProfile().getId())
            .stream()
            .filter(document -> document.getType() != DocumentType.AVATAR)
            .map(document -> {
                document.setData(null);
                return document;
            })
            .toList();
    }

    /**
     * {@code GET /api/personal-document/{id}/content} : the document's bytes, as a stream.
     *
     * <p>The only path bytes leave this service by, and the reason {@link #listOwnDocuments} can
     * afford to null {@code data}. 404 before 403 on an unknown id, deliberately: a caller must not
     * be able to discover that somebody else's document exists by asking for it.
     *
     * @param id the document to stream.
     * @return the bytes, with the stored content type.
     */
    @GetMapping("/{id}/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> streamContent(@PathVariable String id) {
        PersonalDocument document = personalDocumentRepository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        assertOwnerOrReviewer(document);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(document.getDataContentType())).body(document.getData());
    }

    /**
     * The caller is the owning profile, or a reviewer.
     *
     * <p>⚠ <b>This is the one handler on this resource that names a subject</b> — a document id — so
     * unlike the two above it cannot rely on the path for its scoping and has to compare the caller
     * against the record. The {@code ROLE_ADMIN} bypass is the credential reviewer, who has to see
     * the scan to give it a verdict.
     */
    private void assertOwnerOrReviewer(PersonalDocument document) {
        if (SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN)) {
            return;
        }
        String accountId = SecurityUtils.getCurrentAccountId().orElse("");
        boolean owner = profileRepository.findByAccountId(accountId).map(p -> p.getId().equals(document.getProfileId())).orElse(false);
        if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the document owner");
        }
    }

    /**
     * The caller's own profile, or the refusal that makes step 3 depend on step 2.
     *
     * <p>Resolved through {@code SecurityUtils.getCurrentAccountId()} — the {@code uid} claim,
     * discarded when minted by any other issuer, with deliberately no fallback to the login
     * (backlog.md item 50).
     */
    private Profile ownProfile() {
        return profileRepository
            .findByAccountId(currentAccountId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Create your professional profile before uploading documents")
            );
    }

    /**
     * The caller's gateway {@code User.id}, from the {@code uid} claim.
     *
     * <p>Lifted out of {@link #ownProfile} when the avatar path needed the id <em>without</em> that
     * method's refusal — the profile may legitimately not exist yet there. Deliberately no fallback to
     * the login, and a {@code uid} minted by another issuer is already discarded by
     * {@code SecurityUtils} (backlog.md item 50), so a sibling product's token resolves to nothing
     * here and cannot adopt or create a row in this service.
     */
    private String currentAccountId() {
        return SecurityUtils.getCurrentAccountId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
    }

    /**
     * ⛔ An avatar is not uploaded through the generic {@code POST}, and the refusal names where it is.
     *
     * <p>The precedent is {@code POST /api/profiles}, which refuses and names the owner path
     * (backlog.md item 66) rather than storing something subtly wrong and answering 201. What would be
     * subtly wrong here is every difference {@link #uploadAvatar} exists for: the 5 MB ceiling instead
     * of 2 MB, a PDF accepted as a photograph, no metadata stripped — so a clinician's GPS coordinates
     * stored — and no replacement, so avatars piling up beside one another with the readers below
     * unable to say which is current.
     *
     * <p>⚠ Checked <b>before</b> {@link #ownProfile}, so a caller with no profile yet is told about
     * the avatar path rather than about step 2 — the two refusals are both 400 and the useless one
     * would win on ordering alone.
     */
    private void refuseAvatarType(DocumentType type) {
        if (type == DocumentType.AVATAR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload an avatar through POST /api/personal-document/avatar");
        }
    }

    private void validateAvatar(byte[] bytes, String contentType) {
        if (bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty upload");
        }
        if (bytes.length > AVATAR_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar exceeds the 2 MB limit");
        }
        if (contentType == null || !AVATAR_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PNG and JPEG avatars are accepted");
        }
        // The declared type is a claim by the client, so it is held to the content — the same reason
        // the generic path checks it, and here it also decides which ImageIO format the re-encode
        // writes, so a mismatch would be a scrub into the wrong container.
        if (!magicBytesMatch(contentType, bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File content does not match its declared type");
        }
    }

    /**
     * The image bounded, decoded, downscaled and re-encoded — carrying none of the metadata it arrived
     * with, and none of the size it claimed.
     *
     * <h2>⭐ Three jobs, and the first one is why the byte ceiling was never enough</h2>
     *
     * <p>It strips EXIF — which on a phone photograph includes GPS coordinates, and this estate's rule
     * is identifiers and metadata only — it <b>proves the bytes decode as an image</b>, which
     * {@link #magicBytesMatch} cannot (four correct bytes in front of rubbish pass that check and fail
     * here), and it <b>refuses a raster bigger than {@link #MAX_PIXELS} before allocating one</b>.
     *
     * <p>⛔ <b>The bound is read from the header, and that ordering is the whole fix.</b>
     * {@link ImageReader#getWidth(int)} parses a {@code SOF0} or an {@code IHDR} and allocates no
     * raster at all, so an oversized image is refused for the cost of reading a few bytes. ⛔ <b>Do not
     * replace this with a {@code catch} around {@code ImageIO.read}</b>, in either of its tempting
     * forms: catching {@code IOException} does not see an {@code OutOfMemoryError} at all, and catching
     * {@code Error} would still allocate first and would still let case 2 below through — an image that
     * decodes <em>successfully</em> into a third of the heap is a 200, and there is nothing for a
     * handler to catch.
     *
     * <p>⚠ <b>{@code width * height} is computed in {@code long}</b>, and the input that proves it has
     * to be a <b>wide rectangle rather than a big square</b> — which is the trap in checking this.
     * 60000×40000 is 2.4e9, overflowing {@code int} to <b>−1,894,967,296</b>: negative, so a naive
     * comparison passes it straight through to a 2.4-gigapixel decode. ⛔ The obvious candidate,
     * 65535×65535 (the largest a JPEG can declare), does <em>not</em> reach this line at all —
     * measured, the platform reader refuses it on the header read with <i>"Maximum supported image
     * dimension is 65500 pixels"</i>. So a test written with the maximum square would have been green
     * with the overflow bug present, and {@code refusesAFrameWhosePixelCountOverflowsAnInt} uses
     * 60000×40000 for that reason.
     *
     * <h2>And it downscales, so the stored ceiling is a checked property</h2>
     *
     * <p>⛔ <b>Re-encoding can make a file BIGGER</b>, which {@link #AVATAR_MAX_BYTES} alone does not
     * constrain at all: measured in review, a quality-0.05 JPEG arriving under the 2 MB rung came back
     * out at 2,255,157 bytes — a 1.9× amplification — and a sub-filtered PNG at 5.1×. So the bytes
     * checked on the way in were not the bytes stored, and {@code sizeBytes} escaped the ceiling.
     * Downscaling to {@link #AVATAR_RENDER_EDGE} fixes the normal case and the explicit check at the
     * end makes it a property rather than an expectation — a 1024×1024 of incompressible noise can
     * still exceed 2 MB as a PNG, and is refused rather than stored.
     *
     * <p>Measured on JDK 25.0.2: an {@code APP1 Exif} segment carrying a GPS payload injected after
     * {@code SOI} survives into a stored JPEG and is absent after the round trip, with the output still
     * beginning {@code FF D8 FF}; a PNG keeps its 8-byte signature.
     *
     * <p>⚠ <b>Every refusal here is a 400 and that is now true rather than merely intended.</b> It was
     * not before the bound: a 691-byte JPEG claiming 20000×20000 answered <b>500</b>, because the
     * {@code OutOfMemoryError} it raised is an {@link Error} and walked past the {@code catch} below.
     * An image the platform has no reader for — a CMYK JPEG — is likewise refused rather than stored
     * unstripped, because storing it is the one outcome this method exists to prevent. The cost is that
     * an exotic but legitimate photograph is rejected, which a clinician can act on; the alternative
     * silently ships their coordinates.
     */
    private byte[] normalizeImage(byte[] bytes, String contentType) {
        String format = MediaType.IMAGE_PNG_VALUE.equals(contentType) ? "png" : "jpeg";
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The avatar could not be read as an image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The avatar could not be read as an image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                // Header only — no raster exists yet, which is the point.
                long pixels = (long) reader.getWidth(0) * (long) reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Avatar exceeds the " + (MAX_PIXELS / 1_000_000L) + " megapixel limit"
                    );
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The avatar could not be read as an image");
                }
                return encode(renderAtAvatarSize(decoded, format), format);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            // Reading from a byte array, so this is a malformed image rather than a transport fault —
            // 400 and not 500, and the cause is logged because the message cannot carry it.
            log.debug("Avatar could not be read or re-encoded as {}", format, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The avatar could not be read as an image");
        }
    }

    /**
     * The decoded image redrawn at avatar size, in a colour model the target encoder accepts.
     *
     * <p><b>Always redrawn, even when no scaling is needed</b>, and that is deliberate: it normalises
     * the colour model in one place. A JPEG decodes to {@code TYPE_3BYTE_BGR} and a PNG with
     * transparency to {@code TYPE_4BYTE_ABGR}, and handing an alpha-carrying raster to the JPEG writer
     * is the classic way to get either a refusal or silently wrong colours. So JPEG is drawn into
     * {@code TYPE_INT_RGB} and PNG into {@code TYPE_INT_ARGB}, which keeps a transparent avatar
     * transparent.
     *
     * <p>⚠ <b>It never upscales.</b> {@link #AVATAR_RENDER_EDGE} is a ceiling on the longest edge, not
     * a target — enlarging an 80×80 avatar would invent detail and cost bytes for nothing.
     */
    private BufferedImage renderAtAvatarSize(BufferedImage decoded, String format) {
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        int longest = Math.max(width, height);
        if (longest > AVATAR_RENDER_EDGE) {
            double scale = (double) AVATAR_RENDER_EDGE / longest;
            width = Math.max(1, (int) Math.round(width * scale));
            height = Math.max(1, (int) Math.round(height * scale));
        }
        int type = "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage rendered = new BufferedImage(width, height, type);
        Graphics2D graphics = rendered.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(decoded, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return rendered;
    }

    /**
     * The rendered image as bytes, refused if it still exceeds {@link #AVATAR_MAX_BYTES}.
     *
     * <p>⛔ <b>The check is on the OUTPUT, which is the half {@link #validateAvatar} cannot do.</b> That
     * one bounds what arrived; this one bounds what is stored, and re-encoding is not
     * size-preserving — see {@link #normalizeImage}. Without this the ceiling describes a number the
     * service checked and then threw away.
     *
     * <p>⚠ <b>This refusal is defence-in-depth and no test reaches it, which is stated here rather
     * than left for someone to discover.</b> At {@link #AVATAR_RENDER_EDGE} the two bounds do not
     * overlap: 1024×1024 of genuinely incompressible pixels would re-encode above 2 MB, but such an
     * image cannot <em>arrive</em> under the 2 MB input rung at that resolution, and anything
     * compressible enough to arrive re-encodes well below it. So the branch is unreachable by request
     * today <b>because of the downscale, not because output size is bounded in principle</b> — ⛔ raise
     * {@code AVATAR_RENDER_EDGE} and this check becomes the thing that catches it. Keep the two
     * constants in view of each other.
     */
    private byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The avatar could not be re-encoded as " + format);
        }
        byte[] encoded = out.toByteArray();
        if (encoded.length > AVATAR_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar exceeds the 2 MB limit once re-encoded");
        }
        return encoded;
    }

    private void validate(byte[] bytes, String contentType, DocumentType type, String otherLabel, LocalDate expiryDate) {
        if (bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty upload");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document exceeds the 5 MB limit");
        }
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF, PNG and JPEG documents are accepted");
        }
        if (!magicBytesMatch(contentType, bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File content does not match its declared type");
        }
        if (type == DocumentType.OTHER && (otherLabel == null || otherLabel.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A label is required for OTHER documents");
        }
        if (type == DocumentType.LICENSE && expiryDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Licenses require an expiry date");
        }
    }

    /**
     * Whether the bytes begin the way the declared type requires.
     *
     * <p>{@code dataContentType} is whatever the client wrote there, so the allowlist alone admits any
     * file labelled {@code application/pdf}. This is what makes the allowlist mean something, and it
     * is also what {@code mobile/}'s camera pipeline re-encodes for: an iPhone captures HEIC, and
     * HEIC bytes declared {@code image/jpeg} are refused here.
     *
     * <p>⚠ Unchanged by the move to a JSON body — these are the decoded bytes, which is the same
     * array the multipart part yielded.
     */
    private boolean magicBytesMatch(String contentType, byte[] bytes) {
        return switch (contentType) {
            case MediaType.APPLICATION_PDF_VALUE -> bytes.length > 4 &&
            bytes[0] == '%' &&
            bytes[1] == 'P' &&
            bytes[2] == 'D' &&
            bytes[3] == 'F';
            case MediaType.IMAGE_PNG_VALUE -> bytes.length > 8 &&
            (bytes[0] & 0xFF) == 0x89 &&
            bytes[1] == 'P' &&
            bytes[2] == 'N' &&
            bytes[3] == 'G';
            case MediaType.IMAGE_JPEG_VALUE -> bytes.length > 3 &&
            (bytes[0] & 0xFF) == 0xFF &&
            (bytes[1] & 0xFF) == 0xD8 &&
            (bytes[2] & 0xFF) == 0xFF;
            default -> false;
        };
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
