package net.jojoaddison.service;

import java.util.List;
import java.util.Optional;
import net.jojoaddison.domain.HCDocument;
import net.jojoaddison.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service Implementation for managing {@link net.jojoaddison.domain.HCDocument}.
 */
@Service
public class DocumentService {

    private final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * Save a document.
     *
     * @param document the entity to save.
     * @return the persisted entity.
     */
    public HCDocument save(HCDocument document) {
        log.debug("Request to save Document : {}", document);
        return documentRepository.save(document);
    }

    /**
     * Update a document.
     *
     * @param document the entity to save.
     * @return the persisted entity.
     */
    public HCDocument update(HCDocument document) {
        log.debug("Request to update Document : {}", document);
        return documentRepository.save(document);
    }

    /**
     * Partially update a document.
     *
     * @param document the entity to update partially.
     * @return the persisted entity.
     */
    public Optional<HCDocument> partialUpdate(HCDocument document) {
        log.debug("Request to partially update Document : {}", document);

        return documentRepository
            .findById(document.getId())
            .map(existingDocument -> {
                if (document.getName() != null) {
                    existingDocument.setName(document.getName());
                }
                if (document.getProfileId() != null) {
                    existingDocument.setProfileId(document.getProfileId());
                }
                if (document.getData() != null) {
                    existingDocument.setData(document.getData());
                }
                if (document.getDataContentType() != null) {
                    existingDocument.setDataContentType(document.getDataContentType());
                }
                if (document.getType() != null) {
                    existingDocument.setType(document.getType());
                }
                if (document.getCreatedDate() != null) {
                    existingDocument.setCreatedDate(document.getCreatedDate());
                }
                if (document.getModifiedDate() != null) {
                    existingDocument.setModifiedDate(document.getModifiedDate());
                }
                if (document.getLastModifiedBy() != null) {
                    existingDocument.setLastModifiedBy(document.getLastModifiedBy());
                }

                return existingDocument;
            })
            .map(documentRepository::save);
    }

    /**
     * Get all the documents.
     *
     * @return the list of entities.
     */
    public List<HCDocument> findAll() {
        log.debug("Request to get all Documents");
        return documentRepository.findAll();
    }

    /**
     * Get one document by id.
     *
     * @param id the id of the entity.
     * @return the entity.
     */
    public Optional<HCDocument> findOne(String id) {
        log.debug("Request to get Document : {}", id);
        return documentRepository.findById(id);
    }

    /**
     * Delete the document by id.
     *
     * @param id the id of the entity.
     */
    public void delete(String id) {
        log.debug("Request to delete Document : {}", id);
        documentRepository.deleteById(id);
    }
}
