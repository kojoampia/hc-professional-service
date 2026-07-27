package net.jojoaddison.service;

import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.PersonalDocument;
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
