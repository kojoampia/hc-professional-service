package net.jojoaddison.web.rest;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
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
import net.jojoaddison.service.OnboardingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Onboarding document upload and authorized streaming
 * (professional-onboarding-workflow.md § Documents, step 6). Documents are
 * Mongo-resident by decision; validation enforces the allowlist, size limit,
 * and content magic bytes. Bytes are only ever streamed through these
 * authorization checks — never exposed via unauthenticated URLs.
 */
@RestController
@RequestMapping("/api/onboarding/documents")
public class OnboardingDocumentResource {

    private static final Logger log = LoggerFactory.getLogger(OnboardingDocumentResource.class);

    private static final long MAX_BYTES = 5_000_000L;
    private static final Set<String> ALLOWED_TYPES = Set.of(
        MediaType.APPLICATION_PDF_VALUE,
        MediaType.IMAGE_PNG_VALUE,
        MediaType.IMAGE_JPEG_VALUE
    );

    private final PersonalDocumentRepository personalDocumentRepository;
    private final ProfileRepository profileRepository;
    private final DomainEventPublisher domainEventPublisher;

    private final OnboardingService onboardingService;

    public OnboardingDocumentResource(
        PersonalDocumentRepository personalDocumentRepository,
        ProfileRepository profileRepository,
        DomainEventPublisher domainEventPublisher,
        OnboardingService onboardingService
    ) {
        this.personalDocumentRepository = personalDocumentRepository;
        this.profileRepository = profileRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.onboardingService = onboardingService;
    }

    @PostMapping
    public ResponseEntity<PersonalDocument> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam("type") DocumentType type,
        @RequestParam(value = "otherLabel", required = false) String otherLabel,
        @RequestParam(value = "expiryDate", required = false) String expiryDate
    ) throws IOException {
        Profile profile = ownProfile();
        byte[] bytes = file.getBytes();
        validate(file, bytes, type, otherLabel, expiryDate);

        PersonalDocument document = new PersonalDocument()
            .name(file.getOriginalFilename())
            .profileId(profile.getId())
            .type(type)
            .otherLabel(otherLabel)
            .expiryDate(expiryDate == null ? null : LocalDate.parse(expiryDate))
            .sha256Checksum(sha256(bytes))
            .sizeBytes((long) bytes.length)
            .verificationStatus(VerificationStatus.PENDING)
            .createdDate(LocalDate.now());
        document.setData(bytes);
        document.setDataContentType(file.getContentType());
        PersonalDocument saved = personalDocumentRepository.save(document);
        log.debug("Onboarding document {} ({} bytes) uploaded for profile {}", saved.getId(), bytes.length, profile.getId());
        domainEventPublisher.publishEntityCreated(
            "PersonalDocument",
            saved.getId(),
            profile.getAccountId(),
            net.jojoaddison.security.SecurityUtils.getCurrentUserLogin().orElse("system")
        );
        // Never echo the bytes back in the create response.
        saved.setData(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public java.util.List<PersonalDocument> listOwnDocuments() {
        return personalDocumentRepository
            .findByProfileId(ownProfile().getId())
            .stream()
            .map(document -> {
                document.setData(null);
                return document;
            })
            .toList();
    }

    public record RejectRequest(String reason) {}

    @org.springframework.web.bind.annotation.PutMapping("/{id}/verify")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public PersonalDocument verify(@PathVariable String id) {
        PersonalDocument document = onboardingService.verifyDocument(id, currentLogin());
        document.setData(null);
        return document;
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/reject")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public PersonalDocument reject(@PathVariable String id, @org.springframework.web.bind.annotation.RequestBody RejectRequest request) {
        PersonalDocument document = onboardingService.rejectDocument(id, request.reason(), currentLogin());
        document.setData(null);
        return document;
    }

    private String currentLogin() {
        return SecurityUtils.getCurrentUserLogin().orElse("system");
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> streamContent(@PathVariable String id) {
        PersonalDocument document = personalDocumentRepository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        assertOwnerOrReviewer(document);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(document.getDataContentType())).body(document.getData());
    }

    private void assertOwnerOrReviewer(PersonalDocument document) {
        if (SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN)) {
            return;
        }
        String login = SecurityUtils.getCurrentUserLogin().orElse("");
        boolean owner = profileRepository.findByAccountId(login).map(p -> p.getId().equals(document.getProfileId())).orElse(false);
        if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the document owner");
        }
    }

    private Profile ownProfile() {
        String login = SecurityUtils.getCurrentUserLogin()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated account"));
        return profileRepository
            .findByAccountId(login)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Create your professional profile before uploading documents")
            );
    }

    private void validate(MultipartFile file, byte[] bytes, DocumentType type, String otherLabel, String expiryDate) {
        if (bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty upload");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document exceeds the 5 MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF, PNG and JPEG documents are accepted");
        }
        if (!magicBytesMatch(contentType, bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File content does not match its declared type");
        }
        if (type == DocumentType.OTHER && (otherLabel == null || otherLabel.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A label is required for OTHER documents");
        }
        if (type == DocumentType.LICENSE && (expiryDate == null || expiryDate.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Licenses require an expiry date");
        }
    }

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
