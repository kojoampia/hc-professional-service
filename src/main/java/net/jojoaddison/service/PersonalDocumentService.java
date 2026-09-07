package net.jojoaddison.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.PersonalDocument;
import net.jojoaddison.domain.enumeration.DocumentType;
import net.jojoaddison.repository.PersonalDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
     * Save a freshly uploaded document and <b>archive</b> the ones it replaces (backlog.md item 20).
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
     * <p>Newest wins, always: this is called from the upload path, where the clinician is asserting
     * that the file they are sending now is their current credential.
     *
     * @param personalDocument the new document, unsaved.
     * @return the persisted new document.
     */
    public PersonalDocument saveSuperseding(PersonalDocument personalDocument) {
        PersonalDocument saved = personalDocumentRepository.save(personalDocument);
        List<PersonalDocument> replaced = personalDocumentRepository
            .findByProfileId(saved.getProfileId())
            .stream()
            .filter(existing -> supersedes(saved, existing))
            .map(existing -> existing.supersededAt(Instant.now()).supersededByDocumentId(saved.getId()))
            .toList();
        if (!replaced.isEmpty()) {
            personalDocumentRepository.saveAll(replaced);
            log.debug("Document {} superseded {} earlier {} document(s)", saved.getId(), replaced.size(), saved.getType());
        }
        return saved;
    }

    /**
     * Whether {@code replacement} retires {@code existing}: the same credential, on the same profile,
     * still live, and not the replacement itself.
     *
     * <p>Same <em>type</em> and nothing looser. A PASSPORT does not retire a GHANACARD even though
     * both satisfy the identity requirement — they are two documents a clinician legitimately holds
     * at once, and archiving one because the other arrived would hide a credential nobody replaced.
     *
     * <p>{@code OTHER} additionally has to match on {@code otherLabel}, because that type is the
     * free-form catch-all: a police clearance and an indemnity certificate are both {@code OTHER} and
     * neither supersedes the other. The comparison is trimmed and case-insensitive, so "Police
     * clearance" renews "police clearance"; a label that has genuinely been reworded simply leaves the
     * old row live, which is the safe direction to be wrong in.
     */
    private static boolean supersedes(PersonalDocument replacement, PersonalDocument existing) {
        if (existing.getId().equals(replacement.getId()) || existing.getSupersededAt() != null) {
            return false;
        }
        if (existing.getType() != replacement.getType()) {
            return false;
        }
        return replacement.getType() != DocumentType.OTHER || sameLabel(replacement.getOtherLabel(), existing.getOtherLabel());
    }

    private static boolean sameLabel(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.trim().equalsIgnoreCase(right.trim());
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
