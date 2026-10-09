package net.jojoaddison.web.rest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
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
 * that one is the generated CRUD surface, admin-shaped, with {@code data} inline on three reads that
 * check no ownership at all (profile-addendum.md S1); this one is an applicant's own upload, and it
 * <b>never echoes the bytes back</b>.
 *
 * <p>⚠ <b>The singular path is not a tidier spelling of the plural one.</b>
 * {@code /api/personal-document} does not match {@code /api/personal-documents} and must not come to
 * — a rule written here must not widen those unowned reads.
 * {@code ClinicalAuthorityMatrixIT} asserts that in both directions, because it is a claim about
 * Spring's pattern matching rather than about this comment.
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

    private static final Set<String> ALLOWED_TYPES = Set.of(
        MediaType.APPLICATION_PDF_VALUE,
        MediaType.IMAGE_PNG_VALUE,
        MediaType.IMAGE_JPEG_VALUE
    );

    private final PersonalDocumentRepository personalDocumentRepository;
    private final ProfileRepository profileRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final PersonalDocumentService personalDocumentService;

    public OwnPersonalDocumentResource(
        PersonalDocumentRepository personalDocumentRepository,
        ProfileRepository profileRepository,
        DomainEventPublisher domainEventPublisher,
        PersonalDocumentService personalDocumentService
    ) {
        this.personalDocumentRepository = personalDocumentRepository;
        this.profileRepository = profileRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.personalDocumentService = personalDocumentService;
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
     * holds to anything that could read the list at all — which is precisely the shape the plural
     * CRUD surface still has (profile-addendum.md S1).
     *
     * @return the caller's documents, without bytes.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<PersonalDocument> listOwnDocuments() {
        return personalDocumentRepository
            .findByProfileId(ownProfile().getId())
            .stream()
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
        String accountId = SecurityUtils.getCurrentAccountId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
        return profileRepository
            .findByAccountId(accountId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Create your professional profile before uploading documents")
            );
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
