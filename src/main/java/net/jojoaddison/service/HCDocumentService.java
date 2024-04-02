package net.jojoaddison.service;

import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.HCDocument;
import net.jojoaddison.repository.HCDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service Implementation for managing {@link net.jojoaddison.domain.HCDocument}.
 */
@Service
public class HCDocumentService {

    private final Logger log = LoggerFactory.getLogger(HCDocumentService.class);

    private final HCDocumentRepository hCDocumentRepository;

    public HCDocumentService(HCDocumentRepository hCDocumentRepository) {
        this.hCDocumentRepository = hCDocumentRepository;
    }

    /**
     * Save a hCDocument.
     *
     * @param hCDocument the entity to save.
     * @return the persisted entity.
     */
    public HCDocument save(HCDocument hCDocument) {
        log.debug("Request to save HCDocument : {}", hCDocument);
        return hCDocumentRepository.save(hCDocument);
    }

    /**
     * Update a hCDocument.
     *
     * @param hCDocument the entity to save.
     * @return the persisted entity.
     */
    public HCDocument update(HCDocument hCDocument) {
        log.debug("Request to update HCDocument : {}", hCDocument);
        return hCDocumentRepository.save(hCDocument);
    }

    /**
     * Partially update a hCDocument.
     *
     * @param hCDocument the entity to update partially.
     * @return the persisted entity.
     */
    public Optional<HCDocument> partialUpdate(HCDocument hCDocument) {
        log.debug("Request to partially update HCDocument : {}", hCDocument);

        return hCDocumentRepository
            .findById(hCDocument.getId())
            .map(existingHCDocument -> {
                if (hCDocument.getName() != null) {
                    existingHCDocument.setName(hCDocument.getName());
                }
                if (hCDocument.getProfileId() != null) {
                    existingHCDocument.setProfileId(hCDocument.getProfileId());
                }
                if (hCDocument.getData() != null) {
                    existingHCDocument.setData(hCDocument.getData());
                }
                if (hCDocument.getDataContentType() != null) {
                    existingHCDocument.setDataContentType(hCDocument.getDataContentType());
                }
                if (hCDocument.getType() != null) {
                    existingHCDocument.setType(hCDocument.getType());
                }
                if (hCDocument.getCreatedDate() != null) {
                    existingHCDocument.setCreatedDate(hCDocument.getCreatedDate());
                }
                if (hCDocument.getModifiedDate() != null) {
                    existingHCDocument.setModifiedDate(hCDocument.getModifiedDate());
                }
                if (hCDocument.getLastModifiedBy() != null) {
                    existingHCDocument.setLastModifiedBy(hCDocument.getLastModifiedBy());
                }

                return existingHCDocument;
            })
            .map(hCDocumentRepository::save);
    }

    /**
     * Get all the hCDocuments.
     *
     * @return the list of entities.
     */
    public List<HCDocument> findAll() {
        log.debug("Request to get all HCDocuments");
        return hCDocumentRepository.findAll();
    }

    /**
     * Get one hCDocument by id.
     *
     * @param id the id of the entity.
     * @return the entity.
     */
    public Optional<HCDocument> findOne(String id) {
        log.debug("Request to get HCDocument : {}", id);
        return hCDocumentRepository.findById(id);
    }

    /**
     * Delete the hCDocument by id.
     *
     * @param id the id of the entity.
     */
    public void delete(String id) {
        log.debug("Request to delete HCDocument : {}", id);
        hCDocumentRepository.deleteById(id);
    }
}
