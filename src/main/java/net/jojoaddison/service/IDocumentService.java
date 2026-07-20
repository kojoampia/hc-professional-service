package net.jojoaddison.service;

import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.IDocument;
import net.jojoaddison.repository.IDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service Implementation for managing {@link net.jojoaddison.domain.IDocument}.
 */
@Service
public class IDocumentService {

    private final Logger log = LoggerFactory.getLogger(IDocumentService.class);

    private final IDocumentRepository hCDocumentRepository;

    public IDocumentService(IDocumentRepository hCDocumentRepository) {
        this.hCDocumentRepository = hCDocumentRepository;
    }

    /**
     * Save a hCDocument.
     *
     * @param hCDocument the entity to save.
     * @return the persisted entity.
     */
    public IDocument save(IDocument hCDocument) {
        log.debug("Request to save HCDocument : {}", hCDocument);
        return hCDocumentRepository.save(hCDocument);
    }

    /**
     * Update a hCDocument.
     *
     * @param hCDocument the entity to save.
     * @return the persisted entity.
     */
    public IDocument update(IDocument hCDocument) {
        log.debug("Request to update HCDocument : {}", hCDocument);
        return hCDocumentRepository.save(hCDocument);
    }

    /**
     * Partially update a hCDocument.
     *
     * @param hCDocument the entity to update partially.
     * @return the persisted entity.
     */
    public Optional<IDocument> partialUpdate(IDocument hCDocument) {
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
    public List<IDocument> findAll() {
        log.debug("Request to get all HCDocuments");
        return hCDocumentRepository.findAll();
    }

    /**
     * Get one hCDocument by id.
     *
     * @param id the id of the entity.
     * @return the entity.
     */
    public Optional<IDocument> findOne(String id) {
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
