package net.jojoaddison.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.enumeration.VerificationStatus;
import net.jojoaddison.repository.PersonalDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service Implementation for managing {@link net.jojoaddison.domain.PersonalDocument}.
 */
@Service
public class PersonalDocumentService {

    private final Logger log = LoggerFactory.getLogger(PersonalDocumentService.class);

    private final PersonalDocumentRepository personalDocumentRepository;

    public PersonalDocumentService(PersonalDocumentRepository personalDocumentRepository) {
        this.personalDocumentRepository = personalDocumentRepository;
    }

    /**
     * Save a personalDocument.
     *
     * @param personalDocument the entity to save.
     * @return the persisted entity.
     */
    public PersonalDocument save(PersonalDocument personalDocument) {
        log.debug("Request to save PersonalDocument : {}", personalDocument);
        return personalDocumentRepository.save(personalDocument);
    }

    /**
     * Save a freshly uploaded document and <b>archive the one row the clinician says it replaces</b>
     * (backlog.md item 20).
     *
     * <p>Renewing a credential used to be a pure insert, so a profile accumulated one row per renewal
     * for ever and the two admin surfaces that read those rows —
     * {@code ComplianceService.expiringLicenses} and {@code metrics().expiringLicenses30d} — counted
     * every lapsed licence a clinician had ever held. The watchlist filled with entries nobody could
     * clear by doing anything.
     *
     * <p><b>It is a marker, not a delete, and that is the point.</b> A superseded licence is evidence
     * of what a clinician held while they were treating patients; discarding it on upload would be a
     * worse credentialing defect than the lockout item 17 fixed, and it is not recoverable after the
     * fact. The archived row keeps its bytes, its checksum, its expiry and — importantly — the
     * reviewer's {@code verificationStatus} verdict, which is why superseding is its own field pair
     * rather than a fourth {@code VerificationStatus} value that would have overwritten it.
     *
     * <p><b>Nothing is archived unless the caller names the row.</b> This method used to retire every
     * live row of the same type — "newest wins, always" — which reads as a renewal and is not one:
     * <em>the server cannot tell a renewal from a second credential.</em> An ACLS certificate archived
     * a BSc Nursing certificate, and a midwifery registration archived a nursing registration that
     * expired two years later; the archived row then left
     * {@code requireAllMandatoryDocumentsVerified} and the review screen disabled both verify and
     * reject on it, so a genuine second credential could never be given a verdict and never appear on
     * the watchlist. Only the clinician knows which of the documents they hold this one replaces, so
     * only the clinician gets to say. An upload that names nothing simply adds a row, which is what
     * every upload did before item 20 and destroys no information.
     *
     * @param personalDocument the new document, unsaved.
     * @param supersedesDocumentId the caller's own live document this one replaces, or {@code null} to
     *     add without replacing anything.
     * @return the persisted new document.
     */
    public PersonalDocument saveSuperseding(PersonalDocument personalDocument, String supersedesDocumentId) {
        if (supersedesDocumentId == null || supersedesDocumentId.isBlank()) {
            return personalDocumentRepository.save(personalDocument);
        }
        // Validated before the insert, so a refused replacement does not leave a new row behind that
        // the clinician did not get told about.
        PersonalDocument replaced = requireReplaceable(personalDocument, supersedesDocumentId);
        PersonalDocument saved = personalDocumentRepository.save(personalDocument);
        personalDocumentRepository.save(replaced.supersededAt(Instant.now()).supersededByDocumentId(saved.getId()));
        log.debug("Document {} superseded {} ({})", saved.getId(), replaced.getId(), saved.getType());
        return saved;
    }

    /**
     * The named row, or a refusal explaining why it cannot be retired by this upload.
     *
     * <p>Structural checks first — the caller's own profile, still live, the same {@code type} — and
     * then the one that is about credentialing rather than about identifiers: <b>a replacement never
     * retires a document that is better than itself.</b> Precisely, the existing row is kept live when
     * either of these holds:
     *
     * <ol>
     *   <li>both carry an expiry date and the existing one's is <em>later</em> — a credential is not
     *       renewed backwards; or
     *   <li>the existing row is VERIFIED and unexpired while the replacement has already expired.
     * </ol>
     *
     * <p>The second is not implied by the first: a certificate may carry no expiry at all, and an
     * undated VERIFIED row is a current credential that an expired one must not retire.
     *
     * <p>Without this, a clinician who uploads a scan of last year's card — or types {@code 2026} for
     * {@code 2036} in a bare date field — archived the valid licence that was the only thing keeping
     * them out of the nightly sweep, and item 17's guard could no longer save them because
     * {@code isCurrentVerifiedLicense} screens archived rows. Measured on this branch before the fix:
     * the sweep returned {@code {"expiredLicenses":1,"applicationsSuspended":1}} and an ACTIVE
     * clinician who had done nothing wrong was SUSPENDED, recoverable only by re-upload, reviewer
     * verification and admin reactivation. {@code DocumentSupersedeIT}'s
     * {@code aBackdatedUploadDoesNotRetireAValidLicenceNorSuspendTheClinician} is that scenario.
     *
     * <p>It refuses rather than silently skipping because the caller <em>asked</em> for this row to be
     * replaced; doing the upload and quietly not the replacement would tell the clinician they had
     * renewed when they had not. The file is not lost — the message names both dates, which is the
     * information needed to correct the typo and send it again.
     */
    private PersonalDocument requireReplaceable(PersonalDocument replacement, String supersedesDocumentId) {
        PersonalDocument existing = personalDocumentRepository
            .findById(supersedesDocumentId)
            .filter(candidate -> candidate.getProfileId() != null && candidate.getProfileId().equals(replacement.getProfileId()))
            // 404 rather than 403: a caller must not be able to discover that somebody else's document
            // id exists by offering to replace it.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document to replace"));
        if (existing.getSupersededAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That document has already been replaced");
        }
        if (existing.getType() != replacement.getType()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A " + replacement.getType() + " cannot replace a " + existing.getType()
            );
        }
        if (isBetterThan(existing, replacement)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "The document you are replacing is still current (" +
                describeCurrency(existing) +
                "); check the expiry date on the one you are uploading (" +
                describeCurrency(replacement) +
                ")"
            );
        }
        return existing;
    }

    /** See {@link #requireReplaceable} — the two-clause "do not renew backwards" predicate. */
    private static boolean isBetterThan(PersonalDocument existing, PersonalDocument replacement) {
        if (
            existing.getExpiryDate() != null &&
            replacement.getExpiryDate() != null &&
            existing.getExpiryDate().isAfter(replacement.getExpiryDate())
        ) {
            return true;
        }
        return isVerifiedAndUnexpired(existing) && hasExpired(replacement);
    }

    private static boolean isVerifiedAndUnexpired(PersonalDocument document) {
        return document.getVerificationStatus() == VerificationStatus.VERIFIED && !hasExpired(document);
    }

    /** An absent expiry date is not an expired one — most types carry none at all. */
    private static boolean hasExpired(PersonalDocument document) {
        return document.getExpiryDate() != null && document.getExpiryDate().isBefore(LocalDate.now());
    }

    private static String describeCurrency(PersonalDocument document) {
        return document.getExpiryDate() == null ? "no expiry date" : "expires " + document.getExpiryDate();
    }

    /**
     * Whether this row is a live credential rather than an archived one.
     *
     * <p>The single definition, shared by every reader that asks "what does this professional hold
     * <em>now</em>" — {@code OnboardingService}'s completion, approval and licence-currency checks and
     * {@code ComplianceService}'s watchlist. It lives here rather than on {@link PersonalDocument}
     * because that class is generated from {@code .jhipster/PersonalDocument.json} and domain logic
     * there is regenerable-away — the same reasoning that put
     * {@code OnboardingService.isCurrentVerifiedLicense} in a service (backlog.md item 17).
     */
    public static boolean isLive(PersonalDocument document) {
        return document.getSupersededAt() == null;
    }

    /**
     * Update a personalDocument.
     *
     * <p>A whole-document replace, so a {@code PUT} that omits {@code supersededAt} un-archives the row
     * — exactly as it already clears {@code type}, {@code expiryDate} and the reviewer's
     * {@code verificationStatus}. That is the generated CRUD surface behaving as generated and it is
     * not fixed here, but {@code supersededAt} is now what stands between a row and the compliance
     * watchlist, so the blast radius grew: backlog.md item 46.
     *
     * @param personalDocument the entity to save.
     * @return the persisted entity.
     */
    public PersonalDocument update(PersonalDocument personalDocument) {
        log.debug("Request to update PersonalDocument : {}", personalDocument);
        return personalDocumentRepository.save(personalDocument);
    }

    /**
     * Partially update a personalDocument.
     *
     * @param personalDocument the entity to update partially.
     * @return the persisted entity.
     */
    public Optional<PersonalDocument> partialUpdate(PersonalDocument personalDocument) {
        log.debug("Request to partially update PersonalDocument : {}", personalDocument);

        return personalDocumentRepository
            .findById(personalDocument.getId())
            .map(existingPersonalDocument -> {
                if (personalDocument.getName() != null) {
                    existingPersonalDocument.setName(personalDocument.getName());
                }
                if (personalDocument.getProfileId() != null) {
                    existingPersonalDocument.setProfileId(personalDocument.getProfileId());
                }
                if (personalDocument.getData() != null) {
                    existingPersonalDocument.setData(personalDocument.getData());
                }
                if (personalDocument.getDataContentType() != null) {
                    existingPersonalDocument.setDataContentType(personalDocument.getDataContentType());
                }
                if (personalDocument.getType() != null) {
                    existingPersonalDocument.setType(personalDocument.getType());
                }
                if (personalDocument.getCreatedDate() != null) {
                    existingPersonalDocument.setCreatedDate(personalDocument.getCreatedDate());
                }
                if (personalDocument.getModifiedDate() != null) {
                    existingPersonalDocument.setModifiedDate(personalDocument.getModifiedDate());
                }
                if (personalDocument.getLastModifiedBy() != null) {
                    existingPersonalDocument.setLastModifiedBy(personalDocument.getLastModifiedBy());
                }

                return existingPersonalDocument;
            })
            .map(personalDocumentRepository::save);
    }

    /**
     * Get all the personalDocuments.
     *
     * @return the list of entities.
     */
    public List<PersonalDocument> findAll() {
        log.debug("Request to get all PersonalDocuments");
        return personalDocumentRepository.findAll();
    }

    /**
     * Get one personalDocument by id.
     *
     * @param id the id of the entity.
     * @return the entity.
     */
    public Optional<PersonalDocument> findOne(String id) {
        log.debug("Request to get PersonalDocument : {}", id);
        return personalDocumentRepository.findById(id);
    }

    /**
     * Delete the personalDocument by id.
     *
     * @param id the id of the entity.
     */
    public void delete(String id) {
        log.debug("Request to delete PersonalDocument : {}", id);
        personalDocumentRepository.deleteById(id);
    }
}
